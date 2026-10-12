package dev.firefoxis.brewtimer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

/**
 * Таймеры варки и выдержки для BreweryX.
 *
 * Котёл: таймер стартует с первым ингредиентом, мод считает ингредиенты и подбирает рецепт.
 * Бочка: при закрытии окна бочки мод запоминает напитки и время, когда их положили.
 * HUD (правый нижний угол) показывает только таймеры, которым осталась минута или меньше;
 * все таймеры — в окне по клавише (по умолчанию B).
 */
public class BrewTimerClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("brewtimer");

	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("brewtimer", "timers");

	private static final long HUD_THRESHOLD = 60_000L;
	private static final long CAULDRON_MAX_AGE = 2 * 3_600_000L;

	private static final int COLOR_SOON = 0xFFFFFF55;
	private static final int COLOR_LATE = 0xFFFF5555;
	private static final int COLOR_BACKGROUND = 0x90000000;

	private static KeyMapping openKey;
	private static KeyMapping inspectKey;

	/** Последний блок, по которому кликнули (чтобы знать, где стоит открытая бочка). */
	private static BlockPos lastClickedPos;
	private static String lastClickedDimension;
	private static long lastClickedAt;

	/** Котёл, по которому только что кликнули часами: ждём ответ BreweryX в чате. */
	private static BlockPos clockPos;
	private static String clockDimension;
	private static long clockAt;

	private static final java.util.regex.Pattern CLOCK_MINUTES = java.util.regex.Pattern.compile(
			"(?:варит уже|boiling for)\\D*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

	/** Какие оповещения уже прозвучали: «осталась минута» и «готово». */
	private static final Set<String> WARNED_MINUTE = new HashSet<>();
	private static final Set<String> WARNED_DONE = new HashSet<>();

	@Override
	public void onInitializeClient() {
		TimerStore.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("brewtimer", "main"));
		openKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.brewtimer.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
		inspectKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.brewtimer.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

		UseBlockCallback.EVENT.register(BrewTimerClient::onUseBlock);
		ClientTickEvents.END_CLIENT_TICK.register(BrewTimerClient::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> TimerStore.clearCauldrons());
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onGameMessage(message));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> onScreenOpened(screen));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, BrewTimerClient::extractHud);
	}

	public static String dimensionOf(Level level) {
		return level.dimension().identifier().toString();
	}

	// ---------- Котёл ----------

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() || player.isSpectator()) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hit.getBlockPos().immutable();
		String dimension = dimensionOf(level);
		lastClickedPos = pos;
		lastClickedDimension = dimension;
		lastClickedAt = System.currentTimeMillis();

		// BreweryX берёт ингредиенты только из основной руки; вторую руку игра проверяет следом — её пропускаем.
		if (hand != InteractionHand.MAIN_HAND || !level.getBlockState(pos).is(Blocks.WATER_CAULDRON)) {
			return InteractionResult.PASS;
		}

		ItemStack stack = player.getItemInHand(hand);
		TimerStore.Cauldron cauldron = TimerStore.cauldron(dimension, pos);

		if (stack.isEmpty()) {
			// Shift + ПКМ пустой рукой — сбросить таймер вручную.
			if (cauldron != null && player.isShiftKeyDown()) {
				TimerStore.removeCauldron(cauldron);
				chat(player, "Таймер котла сброшен");
			}
			return InteractionResult.PASS;
		}

		if (stack.is(Items.GLASS_BOTTLE)) {
			if (cauldron != null) {
				finishCauldron(player, cauldron);
			}
			return InteractionResult.PASS;
		}

		if (stack.is(Items.CLOCK)) {
			// Часы: BreweryX ответит в чат, сколько котёл уже варит, — ловим это сообщение.
			clockPos = pos;
			clockDimension = dimension;
			clockAt = System.currentTimeMillis();
			return InteractionResult.PASS;
		}

		// Вода, пустое ведро и зелья ингредиентами не считаются.
		if (stack.is(Items.WATER_BUCKET) || stack.is(Items.BUCKET) || stack.is(Items.POTION)) {
			return InteractionResult.PASS;
		}

		String key = Recipes.keyOf(stack);
		if (cauldron == null) {
			// Клик предметом не из рецептов (меч, кирка…) таймер не запускает.
			if (!Recipes.INGREDIENTS.containsKey(key)) {
				return InteractionResult.PASS;
			}
			cauldron = TimerStore.startCauldron(dimension, pos);
			chat(player, "Таймер котла запущен");
		}
		// BreweryX забирает по одному предмету за клик.
		cauldron.contents.merge(key, 1, Integer::sum);

		Recipes.Match match = cauldron.match();
		String status = match.exact()
				? match.title() + (match.hints().isEmpty() ? " — всё верно" : " — " + String.join("; ", match.hints()))
				: String.join("; ", match.hints());
		player.sendOverlayMessage(Component.literal(status));

		// PASS — сервер и плагин обрабатывают клик как обычно.
		return InteractionResult.PASS;
	}

	private static void finishCauldron(Player player, TimerStore.Cauldron cauldron) {
		TimerStore.removeCauldron(cauldron);
		long elapsed = System.currentTimeMillis() - cauldron.start;
		Recipes.Match match = cauldron.match();
		Recipes.Recipe recipe = cauldron.recipe();

		if (recipe != null) {
			long diff = elapsed - recipe.cookMillis();
			String timing = Math.abs(diff) < 30_000L ? "точно в срок"
					: diff < 0 ? "раньше на " + Recipes.formatClock(-diff)
					: "позже на " + Recipes.formatClock(diff);
			chat(player, (match.exact() ? match.title() : recipe.name()) + ": разлито через " + Recipes.formatClock(elapsed) + " (" + timing + ")");
			chat(player, "Дальше: " + recipe.nextSteps());
			TimerStore.rememberBrewed(recipe);
		} else {
			chat(player, "Разлито через " + Recipes.formatClock(elapsed) + ", рецепт не распознан");
		}
	}

	private static void onGameMessage(Component message) {
		if (clockPos == null || System.currentTimeMillis() - clockAt > 3_000L) {
			return;
		}
		String text = message.getString();
		String lower = text.toLowerCase(Locale.ROOT);

		int minutes;
		java.util.regex.Matcher m = CLOCK_MINUTES.matcher(text);
		if (m.find()) {
			minutes = Integer.parseInt(m.group(1));
		} else if (lower.contains("только начал") || lower.contains("just started")) {
			minutes = -1;
		} else {
			return;
		}

		BlockPos pos = clockPos;
		String dimension = clockDimension;
		clockPos = null;

		TimerStore.Cauldron cauldron = TimerStore.applyClock(dimension, pos, minutes);
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			Recipes.Recipe recipe = cauldron.recipe();
			String status = "Котёл варит ~" + Recipes.formatClock(System.currentTimeMillis() - cauldron.start);
			if (recipe != null) {
				long left = recipe.cookMillis() - (System.currentTimeMillis() - cauldron.start);
				status += left > 0 ? ", " + recipe.name() + " — осталось " + Recipes.formatClock(left)
						: ", " + recipe.name() + " — пора разливать!";
			} else {
				status += ". Рецепт неизвестен — выберите его в окне таймеров (B)";
			}
			chat(client.player, status);
		}
	}

	// ---------- Бочка ----------

	private static void onScreenOpened(net.minecraft.client.gui.screens.Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return;
		}

		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		boolean looksLikeBarrel = title.contains("бочк") || title.contains("barrel");
		if (!looksLikeBarrel || lastClickedPos == null || System.currentTimeMillis() - lastClickedAt > 5_000L) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			BlockState state = client.level.getBlockState(lastClickedPos);
			// Ванильная бочка — обычный сундук, BreweryX-бочки собираются из досок, ступенек и заборов.
			if (state.is(Blocks.BARREL)) {
				return;
			}
		}

		BlockPos pos = lastClickedPos;
		String dimension = lastClickedDimension;
		ScreenEvents.remove(screen).register(closed -> captureBarrel(containerScreen, dimension, pos));
	}

	private static void captureBarrel(AbstractContainerScreen<?> screen, String dimension, BlockPos pos) {
		Map<Integer, TimerStore.DrinkReading> drinks = new LinkedHashMap<>();
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container instanceof Inventory) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.is(Items.POTION)) {
				drinks.put(slot.index, new TimerStore.DrinkReading(stack.getHoverName().getString(), BrewData.read(stack)));
			}
		}
		TimerStore.updateBarrel(dimension, pos, drinks);
	}

	// ---------- Тик: уборка и оповещения ----------

	private static void onTick(Minecraft client) {
		while (openKey.consumeClick()) {
			if (client.player != null) {
				client.gui.setScreen(new TimersScreen());
			}
		}

		while (inspectKey.consumeClick()) {
			if (client.player != null) {
				inspectHeldItem(client.player);
			}
		}

		if (client.level == null || client.player == null) {
			return;
		}

		long now = System.currentTimeMillis();
		String dimension = dimensionOf(client.level);

		// Котёл сломали или вылили — убираем таймер (только если чанк загружен).
		Iterator<TimerStore.Cauldron> it = TimerStore.cauldronIterator();
		while (it.hasNext()) {
			TimerStore.Cauldron cauldron = it.next();
			boolean gone = cauldron.dimension.equals(dimension) && client.level.isLoaded(cauldron.pos)
					&& !client.level.getBlockState(cauldron.pos).is(Blocks.WATER_CAULDRON);
			if (gone || now - cauldron.start > CAULDRON_MAX_AGE) {
				it.remove();
			}
		}

		for (TimerStore.View view : TimerStore.views(dimension)) {
			if (!view.known()) {
				continue;
			}
			if (view.remaining() <= HUD_THRESHOLD && view.remaining() > 0 && WARNED_MINUTE.add(view.id())) {
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0F));
			}
			if (view.remaining() <= 0 && WARNED_DONE.add(view.id())) {
				WARNED_MINUTE.add(view.id());
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0F));
				String action = view.kind().equals("Котёл") ? "пора разливать!" : "пора доставать из бочки!";
				chat(client.player, view.title() + " [" + view.location() + "] — " + action);
			}
		}
	}

	// ---------- HUD ----------

	private static void extractHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}

		List<TimerStore.View> urgent = new ArrayList<>();
		for (TimerStore.View view : TimerStore.views(dimensionOf(minecraft.level))) {
			if (view.known() && view.remaining() <= HUD_THRESHOLD) {
				urgent.add(view);
			}
		}
		if (urgent.isEmpty()) {
			return;
		}

		Font font = minecraft.font;
		int lineHeight = font.lineHeight + 2;
		List<String> lines = new ArrayList<>();
		int width = 0;
		for (TimerStore.View view : urgent) {
			String line = view.kind() + ": " + view.title() + "  " + timeText(view);
			lines.add(line);
			width = Math.max(width, font.width(line));
		}

		int margin = 6;
		int right = graphics.guiWidth() - margin;
		int bottom = graphics.guiHeight() - margin;
		int top = bottom - lineHeight * lines.size();
		graphics.fill(right - width - 4, top - 3, right + 3, bottom + 1, COLOR_BACKGROUND);

		int y = top;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int color = urgent.get(i).remaining() <= 0 ? COLOR_LATE : COLOR_SOON;
			graphics.text(font, line, right - font.width(line), y, color, true);
			y += lineHeight;
		}
	}

	/** «0:45» до готовности или «пора! +1:10», если время вышло. */
	public static String timeText(TimerStore.View view) {
		if (!view.known()) {
			return "идёт " + Recipes.formatClock(view.elapsed());
		}
		if (view.remaining() > 0) {
			return Recipes.formatClock(view.remaining());
		}
		return "пора! +" + Recipes.formatClock(-view.remaining());
	}

	/** Отладка: что мод видит в напитке в основной руке. Подробности — в config/brewtimer-debug.txt. */
	private static void inspectHeldItem(Player player) {
		ItemStack stack = player.getMainHandItem();
		if (stack.isEmpty()) {
			chat(player, "Возьмите напиток в основную руку и нажмите клавишу ещё раз");
			return;
		}

		List<String> lines = BrewData.describe(stack);
		for (String line : lines) {
			chat(player, line);
		}

		StringBuilder dump = new StringBuilder();
		dump.append("===== ").append(java.time.LocalDateTime.now()).append(" =====\n");
		for (String line : lines) {
			dump.append(line).append('\n');
		}
		dump.append("Компоненты: ").append(stack.getComponents()).append('\n');
		byte[] raw = BrewData.rawTag(stack);
		if (raw != null) {
			StringBuilder hex = new StringBuilder();
			for (byte b : raw) {
				hex.append(String.format("%02x ", b));
			}
			dump.append("brewdata (hex): ").append(hex.toString().trim()).append('\n');
		}
		dump.append('\n');

		java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("brewtimer-debug.txt");
		try {
			java.nio.file.Files.writeString(file, dump.toString(), java.nio.charset.StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			chat(player, "Подробный отчёт дописан в config/brewtimer-debug.txt");
		} catch (java.io.IOException e) {
			LOGGER.warn("Не удалось записать {}", file, e);
		}
		LOGGER.info("[brewtimer] {}", dump);
	}

	public static void forgetWarnings(String id) {
		WARNED_MINUTE.remove(id);
		WARNED_DONE.remove(id);
	}

	private static void chat(Player player, String text) {
		player.sendSystemMessage(Component.literal("[Варка] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
	}
}

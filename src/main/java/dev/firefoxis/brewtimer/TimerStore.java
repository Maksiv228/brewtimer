package dev.firefoxis.brewtimer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Все таймеры мода: котлы (в памяти) и бочки (сохраняются в config/brewtimer-barrels.json,
 * потому что выдержка длится часами и переживает перезаход в игру).
 */
public final class TimerStore {
	/** Значение remaining у таймера без известной цели. */
	public static final long UNKNOWN = Long.MIN_VALUE;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("brewtimer-barrels.json");

	// ---------- Модель ----------

	public static final class Cauldron {
		public final String dimension;
		public final BlockPos pos;
		public final long start;
		public final Map<String, Integer> contents = new LinkedHashMap<>();

		Cauldron(String dimension, BlockPos pos, long start) {
			this.dimension = dimension;
			this.pos = pos;
			this.start = start;
		}

		public String id() {
			return "cauldron:" + dimension + ":" + pos.asLong();
		}

		public Recipes.Match match() {
			return Recipes.match(contents);
		}
	}

	public static final class BarrelDrink {
		public int slot;
		public String name;
		public long start;
		/** Определённый рецепт или null. */
		public String recipeId;
		/** true — рецепт взят из последней варки, а не по названию. */
		public boolean guessed;
	}

	public static final class Barrel {
		public String dimension;
		public int x;
		public int y;
		public int z;
		public List<BarrelDrink> drinks = new ArrayList<>();

		boolean near(String dim, BlockPos pos) {
			return dimension.equals(dim)
					&& Math.abs(x - pos.getX()) <= 3 && Math.abs(y - pos.getY()) <= 3 && Math.abs(z - pos.getZ()) <= 3;
		}
	}

	private static final class SaveData {
		List<Barrel> barrels = new ArrayList<>();
		String lastBrewedRecipe;
		long lastBrewedAt;
	}

	/** Одна строка для экрана и HUD. */
	public record View(String id, String kind, String title, long remaining, long elapsed, String location,
			List<String> details, Runnable remove) {

		public boolean known() {
			return remaining != UNKNOWN;
		}
	}

	// ---------- Состояние ----------

	private static final Map<String, Cauldron> CAULDRONS = new LinkedHashMap<>();
	private static SaveData data = new SaveData();

	private TimerStore() {
	}

	// ---------- Котлы ----------

	public static Cauldron cauldron(String dimension, BlockPos pos) {
		return CAULDRONS.get("cauldron:" + dimension + ":" + pos.asLong());
	}

	public static Cauldron startCauldron(String dimension, BlockPos pos) {
		Cauldron cauldron = new Cauldron(dimension, pos.immutable(), System.currentTimeMillis());
		CAULDRONS.put(cauldron.id(), cauldron);
		return cauldron;
	}

	public static void removeCauldron(Cauldron cauldron) {
		CAULDRONS.remove(cauldron.id());
	}

	public static Iterator<Cauldron> cauldronIterator() {
		return CAULDRONS.values().iterator();
	}

	public static void clearCauldrons() {
		CAULDRONS.clear();
	}

	/** Запоминаем, что только что разлили — пригодится, если бочка не узнает напиток по названию. */
	public static void rememberBrewed(Recipes.Recipe recipe) {
		data.lastBrewedRecipe = recipe.id();
		data.lastBrewedAt = System.currentTimeMillis();
		save();
	}

	// ---------- Бочки ----------

	/** Обновляет содержимое бочки после закрытия её окна. slots: номер слота → название напитка. */
	public static void updateBarrel(String dimension, BlockPos pos, Map<Integer, String> slots) {
		Barrel barrel = null;
		for (Barrel b : data.barrels) {
			if (b.near(dimension, pos)) {
				barrel = b;
				break;
			}
		}

		if (barrel == null) {
			if (slots.isEmpty()) {
				return;
			}
			barrel = new Barrel();
			barrel.dimension = dimension;
			barrel.x = pos.getX();
			barrel.y = pos.getY();
			barrel.z = pos.getZ();
			data.barrels.add(barrel);
		}

		long now = System.currentTimeMillis();
		Map<Integer, BarrelDrink> old = new LinkedHashMap<>();
		for (BarrelDrink drink : barrel.drinks) {
			old.put(drink.slot, drink);
		}

		List<BarrelDrink> updated = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : slots.entrySet()) {
			BarrelDrink previous = old.get(entry.getKey());
			if (previous != null && previous.name.equals(entry.getValue())) {
				updated.add(previous);
				continue;
			}

			BarrelDrink drink = new BarrelDrink();
			drink.slot = entry.getKey();
			drink.name = entry.getValue();
			drink.start = now;

			Recipes.Recipe recipe = Recipes.byDrinkName(drink.name);
			if (recipe == null && data.lastBrewedRecipe != null && now - data.lastBrewedAt < 3 * 3_600_000L) {
				Recipes.Recipe last = Recipes.byId(data.lastBrewedRecipe);
				if (last != null && last.ageYears() > 0) {
					recipe = last;
					drink.guessed = true;
				}
			}
			drink.recipeId = recipe == null ? null : recipe.id();
			updated.add(drink);
		}

		barrel.drinks = updated;
		if (updated.isEmpty()) {
			data.barrels.remove(barrel);
		}
		save();
	}

	// ---------- Список для отображения ----------

	public static List<View> views(String currentDimension) {
		long now = System.currentTimeMillis();
		List<View> views = new ArrayList<>();

		for (Cauldron cauldron : new ArrayList<>(CAULDRONS.values())) {
			Recipes.Match match = cauldron.match();
			long elapsed = now - cauldron.start;
			long remaining = match.exact() ? match.best().cookMillis() - elapsed : UNKNOWN;

			List<String> details = new ArrayList<>();
			details.add("В котле: " + describeContents(cauldron.contents));
			details.addAll(match.hints());
			if (match.exact()) {
				details.add("Варить " + match.best().cookMinutes() + " мин, дальше: " + match.best().nextSteps());
			}

			views.add(new View(cauldron.id(), "Котёл", match.title(), remaining, elapsed, coords(cauldron.pos),
					details, () -> CAULDRONS.remove(cauldron.id())));
		}

		for (Barrel barrel : new ArrayList<>(data.barrels)) {
			// Группируем одинаковые напитки, положенные одновременно.
			Map<String, List<BarrelDrink>> groups = new LinkedHashMap<>();
			for (BarrelDrink drink : barrel.drinks) {
				String key = drink.name + "|" + drink.recipeId + "|" + (drink.start / 10_000L);
				groups.computeIfAbsent(key, k -> new ArrayList<>()).add(drink);
			}

			for (Map.Entry<String, List<BarrelDrink>> group : groups.entrySet()) {
				List<BarrelDrink> drinks = group.getValue();
				BarrelDrink first = drinks.get(0);
				Recipes.Recipe recipe = first.recipeId == null ? null : Recipes.byId(first.recipeId);
				long elapsed = now - first.start;
				long remaining = recipe != null ? recipe.ageMillis() - elapsed : UNKNOWN;

				String title = (recipe != null ? recipe.name() : first.name) + (drinks.size() > 1 ? " ×" + drinks.size() : "");
				List<String> details = new ArrayList<>();
				details.add("В бочке: " + first.name);
				if (recipe != null) {
					long years = elapsed / (Recipes.MINUTES_PER_YEAR * 60_000L);
					details.add("Нужно: " + recipe.wood() + ", " + recipe.ageYears() + " "
							+ Recipes.plural(recipe.ageYears(), "год", "года", "лет")
							+ " — прошло " + years + " " + Recipes.plural(years, "год", "года", "лет"));
					if (first.guessed) {
						details.add("рецепт определён по последней варке");
					}
				} else {
					details.add("рецепт не определён — показываю только прошедшее время");
				}

				Barrel owner = barrel;
				views.add(new View("barrel:" + barrel.dimension + ":" + barrel.x + ":" + barrel.y + ":" + barrel.z + ":" + group.getKey(),
						"Бочка", title, remaining, elapsed,
						barrel.x + " " + barrel.y + " " + barrel.z + (barrel.dimension.equals(currentDimension) ? "" : " (другое измерение)"),
						details, () -> {
							owner.drinks.removeAll(drinks);
							if (owner.drinks.isEmpty()) {
								data.barrels.remove(owner);
							}
							save();
						}));
			}
		}

		// Сначала то, что скоро будет готово.
		views.sort((a, b) -> {
			if (a.known() != b.known()) {
				return a.known() ? -1 : 1;
			}
			return a.known() ? Long.compare(a.remaining(), b.remaining()) : Long.compare(b.elapsed(), a.elapsed());
		});
		return views;
	}

	public static String coords(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static String describeContents(Map<String, Integer> contents) {
		if (contents.isEmpty()) {
			return "пусто";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> e : contents.entrySet()) {
			parts.add(Recipes.nameOf(e.getKey()) + " ×" + e.getValue());
		}
		return String.join(", ", parts);
	}

	// ---------- Сохранение ----------

	public static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			SaveData loaded = GSON.fromJson(reader, SaveData.class);
			if (loaded != null) {
				if (loaded.barrels == null) {
					loaded.barrels = new ArrayList<>();
				}
				data = loaded;
			}
		} catch (IOException | RuntimeException e) {
			BrewTimerClient.LOGGER.warn("Не удалось прочитать {}", FILE, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			BrewTimerClient.LOGGER.warn("Не удалось сохранить {}", FILE, e);
		}
	}
}

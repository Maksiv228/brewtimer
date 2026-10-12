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
		public long start;
		public final Map<String, Integer> contents = new LinkedHashMap<>();
		/** Рецепт, выбранный вручную в окне (для котла, начатого без мода). */
		public Recipes.Recipe recipeOverride;
		/** Таймер создан по часам — ингредиенты мод не видел. */
		public boolean fromClock;
		/** Последнее показание часов BreweryX (минуты, -1 — «только начал») и когда. */
		public Integer clockMinutes;
		public long clockAt;

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

		/** Рецепт, по которому идёт отсчёт: распознанный по ингредиентам или выбранный вручную. */
		public Recipes.Recipe recipe() {
			Recipes.Match match = match();
			return match.exact() ? match.best() : recipeOverride;
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
		/** Откуда известен возраст: tag — из скрытых данных, lore — из описания, start — от момента закладки. */
		public String source = "start";
		/** Последнее прочитанное из описания число лет и когда. */
		public Integer loreYears;
		public long loreAt;
	}

	/** Что мод увидел в слоте бочки при закрытии. */
	public record DrinkReading(String name, BrewData.Result data) {
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
			List<String> details, Runnable remove, Runnable prevRecipe, Runnable nextRecipe) {

		public View(String id, String kind, String title, long remaining, long elapsed, String location,
				List<String> details, Runnable remove) {
			this(id, kind, title, remaining, elapsed, location, details, remove, null, null);
		}

		public boolean canPickRecipe() {
			return prevRecipe != null && nextRecipe != null;
		}

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

	/** Обновляет содержимое бочки после закрытия её окна. slots: номер слота → что в нём лежит. */
	public static void updateBarrel(String dimension, BlockPos pos, Map<Integer, DrinkReading> slots) {
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
		for (Map.Entry<Integer, DrinkReading> entry : slots.entrySet()) {
			DrinkReading reading = entry.getValue();
			BrewData.Tag tag = reading.data().tag();

			// Рецепт: из скрытых данных → по названию → прежний для этого слота.
			Recipes.Recipe recipe = tag != null && tag.recipe() != null ? Recipes.byDrinkName(tag.recipe()) : null;
			if (recipe == null) {
				recipe = Recipes.byDrinkName(reading.name());
			}

			BarrelDrink previous = old.get(entry.getKey());
			boolean samePrevious = previous != null && (previous.name.equals(reading.name())
					|| (recipe != null && recipe.id().equals(previous.recipeId)));

			BarrelDrink drink;
			if (samePrevious) {
				drink = previous;
				drink.name = reading.name();
			} else {
				drink = new BarrelDrink();
				drink.slot = entry.getKey();
				drink.name = reading.name();
				drink.start = now;
			}

			if (recipe != null) {
				drink.recipeId = recipe.id();
				drink.guessed = false;
			} else if (drink.recipeId == null && data.lastBrewedRecipe != null && now - data.lastBrewedAt < 3 * 3_600_000L) {
				Recipes.Recipe last = Recipes.byId(data.lastBrewedRecipe);
				if (last != null && last.ageYears() > 0) {
					drink.recipeId = last.id();
					drink.guessed = true;
				}
			}

			correctAge(drink, reading.data(), now);
			updated.add(drink);
		}

		barrel.drinks = updated;
		if (updated.isEmpty()) {
			data.barrels.remove(barrel);
		}
		save();
	}

	private static Recipes.Recipe cycle(Recipes.Recipe current, int step) {
		List<Recipes.Recipe> all = Recipes.RECIPES;
		int index = current == null ? (step > 0 ? -1 : 0) : all.indexOf(current);
		return all.get(Math.floorMod(index + step, all.size()));
	}

	/**
	 * Котёл проверили часами. minutes — сколько минут насчитал BreweryX, или -1 для «только начал варить».
	 * Счётчик BreweryX растёт на 1 каждую минуту нагрева, поэтому «N минут» значит от N до N+1.
	 */
	public static Cauldron applyClock(String dimension, BlockPos pos, int minutes) {
		long now = System.currentTimeMillis();
		Cauldron cauldron = cauldron(dimension, pos);
		if (cauldron == null) {
			cauldron = startCauldron(dimension, pos);
			cauldron.fromClock = true;
		}

		double low = minutes < 0 ? 0 : minutes;
		double high = minutes < 0 ? 2 : minutes + 1;
		double estimate = (now - cauldron.start) / 60_000.0;
		double age;
		if (minutes >= 2 && cauldron.clockMinutes != null && minutes == cauldron.clockMinutes + 1 && now > cauldron.clockAt) {
			// Показание сменилось с прошлой проверки: минута перещёлкнула между проверками.
			double sinceLast = (now - cauldron.clockAt) / 60_000.0;
			age = minutes + Math.min(sinceLast, 1.0) / 2.0;
		} else if (estimate >= low && estimate < high) {
			age = estimate;
		} else {
			age = (low + high) / 2.0;
		}

		cauldron.start = now - (long) (age * 60_000.0);
		cauldron.clockMinutes = minutes;
		cauldron.clockAt = now;
		return cauldron;
	}

	/** Подправляет время закладки по тому, что прочитано из напитка. */
	private static void correctAge(BarrelDrink drink, BrewData.Result reading, long now) {
		long yearMs = Recipes.MINUTES_PER_YEAR * 60_000L;

		if (reading.tag() != null) {
			// Точный дробный возраст из скрытых данных.
			drink.start = now - (long) (reading.tag().ageYears() * yearMs);
			drink.source = "tag";
			return;
		}

		Integer years = reading.loreYears();
		if (years == null) {
			return;
		}

		double estimate = (now - drink.start) / (double) yearMs;
		double age;
		if (drink.loreYears != null && years == drink.loreYears + 1 && now > drink.loreAt) {
			// Число лет сменилось с прошлого открытия: переход был между тем открытием и этим.
			double sinceLast = (now - drink.loreAt) / (double) yearMs;
			age = years + Math.min(sinceLast, 1.0) / 2.0;
		} else if (estimate >= years && estimate < years + 1) {
			// Наша оценка не противоречит описанию — оставляем как есть.
			age = estimate;
		} else {
			// Расходится больше чем на год — ставим середину прочитанного года.
			age = years + 0.5;
		}

		drink.start = now - (long) (age * yearMs);
		drink.source = "lore";
		drink.loreYears = years;
		drink.loreAt = now;
	}

	// ---------- Список для отображения ----------

	public static List<View> views(String currentDimension) {
		long now = System.currentTimeMillis();
		List<View> views = new ArrayList<>();

		for (Cauldron cauldron : new ArrayList<>(CAULDRONS.values())) {
			Recipes.Match match = cauldron.match();
			Recipes.Recipe recipe = cauldron.recipe();
			long elapsed = now - cauldron.start;
			long remaining = recipe != null ? recipe.cookMillis() - elapsed : UNKNOWN;
			String title = match.exact() ? match.title() : recipe != null ? recipe.name() : "Котёл";

			List<String> details = new ArrayList<>();
			if (cauldron.fromClock && cauldron.contents.isEmpty()) {
				details.add("Ингредиенты неизвестны — таймер создан по часам");
			} else {
				details.add("В котле: " + describeContents(cauldron.contents));
				details.addAll(match.hints());
			}
			if (!match.exact()) {
				details.add(recipe != null ? "рецепт выбран вручную (◀ ▶)" : "выберите рецепт кнопками ◀ ▶, чтобы шёл обратный отсчёт");
			}
			if (recipe != null) {
				details.add("Варить " + recipe.cookMinutes() + " мин, дальше: " + recipe.nextSteps());
			}
			if (cauldron.clockMinutes != null) {
				details.add("по часам BreweryX: " + (cauldron.clockMinutes < 0 ? "только начал" : cauldron.clockMinutes + " мин")
						+ " (±30 с, точнее при повторной проверке)");
			}

			Runnable prev = null;
			Runnable next = null;
			if (!match.exact()) {
				prev = () -> cauldron.recipeOverride = cycle(cauldron.recipeOverride, -1);
				next = () -> cauldron.recipeOverride = cycle(cauldron.recipeOverride, 1);
			}

			views.add(new View(cauldron.id(), "Котёл", title, remaining, elapsed, coords(cauldron.pos),
					details, () -> CAULDRONS.remove(cauldron.id()), prev, next));
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
					details.add(switch (first.source == null ? "start" : first.source) {
						case "tag" -> "возраст из данных напитка (точно)";
						case "lore" -> "возраст из описания напитка (точность до ±10 мин)";
						default -> "возраст считается от момента, когда напиток положили";
					});
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

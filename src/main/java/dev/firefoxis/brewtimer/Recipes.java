package dev.firefoxis.brewtimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Стандартные рецепты BreweryX (recipes.yml по умолчанию).
 * Если на сервере рецепты изменены — правьте таблицу RECIPES.
 */
public final class Recipes {
	/** Сколько реальных минут длится один «год» выдержки (agingYearDuration в config.yml BreweryX). */
	public static final int MINUTES_PER_YEAR = 20;

	/** Ингредиент: ключ, название по-русски и предметы, которые им считаются. */
	public record Ingredient(String key, String name, Set<Item> items) {
	}

	/** Рецепт напитка. */
	public record Recipe(String id, String name, List<String> serverNames, Map<String, Integer> ingredients,
			int cookMinutes, int distillRuns, String wood, int ageYears) {

		public long cookMillis() {
			return cookMinutes * 60_000L;
		}

		public long ageMillis() {
			return ageYears * (long) MINUTES_PER_YEAR * 60_000L;
		}

		/** Что делать после того, как напиток разлит из котла. */
		public String nextSteps() {
			List<String> steps = new ArrayList<>();
			if (distillRuns > 0) {
				steps.add("дистилляция " + distillRuns + " " + Recipes.plural(distillRuns, "цикл", "цикла", "циклов"));
			}
			if (ageYears > 0) {
				steps.add(wood + ", " + ageYears + " " + Recipes.plural(ageYears, "год", "года", "лет")
						+ " (" + Recipes.formatDuration(ageMillis()) + ")");
			}
			return steps.isEmpty() ? "готово, можно пить" : String.join(", затем ", steps);
		}
	}

	public static final Map<String, Ingredient> INGREDIENTS = new LinkedHashMap<>();
	public static final List<Recipe> RECIPES = new ArrayList<>();

	static {
		ing("wheat", "Пшеница", Items.WHEAT);
		ing("sugar_cane", "Тростник", Items.SUGAR_CANE);
		ing("sweet_berries", "Сладкие ягоды", Items.SWEET_BERRIES);
		ing("apple", "Яблоко", Items.APPLE);
		ing("potato", "Картофель", Items.POTATO);
		ing("red_mushroom", "Красный гриб", Items.RED_MUSHROOM);
		ing("brown_mushroom", "Коричневый гриб", Items.BROWN_MUSHROOM);
		ing("blue_flowers", "Синие цветы", Items.CORNFLOWER, Items.BLUE_ORCHID);
		ing("cactus", "Кактус", Items.CACTUS);
		ing("grass", "Трава", Items.SHORT_GRASS);
		ing("poisonous_potato", "Ядовитый картофель", Items.POISONOUS_POTATO);
		ing("cocoa_beans", "Какао-бобы", Items.COCOA_BEANS);
		ing("milk_bucket", "Ведро молока", Items.MILK_BUCKET);
		ing("egg", "Яйцо", Items.EGG);
		ing("sugar", "Сахар", Items.SUGAR);
		ing("gold_nugget", "Золотой самородок", Items.GOLD_NUGGET);
		ing("blaze_powder", "Огненный порошок", Items.BLAZE_POWDER);
		ing("cookie", "Печенье", Items.COOKIE);
		ing("snowball", "Снежок", Items.SNOWBALL);

		String any = "Любая бочка";
		String none = "-";

		add("wheatbeer", "Пшеничное пиво", names("Skunky Wheatbeer", "Wheatbeer", "Fine Wheatbeer"),
				map("wheat", 3), 8, 0, "Берёзовая бочка", 2);
		add("beer", "Пиво", names("Skunky Beer", "Beer", "Fine Beer"),
				map("wheat", 6), 8, 0, any, 3);
		add("darkbeer", "Тёмное пиво", names("Skunky Darkbeer", "Darkbeer", "Fine Darkbeer"),
				map("wheat", 6), 8, 0, "Бочка из тёмного дуба", 8);
		add("wine", "Вино", names("Red Wine"),
				map("sweet_berries", 5), 5, 0, any, 20);
		add("mead", "Медовуха", names("Awkward Mead", "Mead", "Golden Mead"),
				map("sugar_cane", 6), 3, 0, "Дубовая бочка", 4);
		add("ap_mead", "Яблочная медовуха", names("Apple Mead", "Sweet Apple Mead", "Sweet Golden Apple Mead"),
				map("sugar_cane", 6, "apple", 2), 4, 0, "Дубовая бочка", 4);
		add("cidre", "Яблочный сидр", names("Poor Cidre", "Apple Cider", "Great Apple Cider"),
				map("apple", 14), 7, 0, any, 3);
		add("apple_liquor", "Яблочный ликёр", names("Sour Apple Liquor", "Apple Liquor", "Calvados"),
				map("apple", 12), 16, 3, "Бочка из акации", 6);
		add("whiskey", "Виски", names("Unsightly Whiskey", "Whiskey", "Scotch Whiskey"),
				map("wheat", 10), 10, 2, "Еловая бочка", 18);
		add("rum", "Ром", names("Bitter Rum", "Spicy Rum", "Golden Rum"),
				map("sugar_cane", 18), 6, 2, "Дубовая бочка", 14);
		add("vodka", "Водка", names("Lousy Vodka", "Vodka", "Russian Vodka"),
				map("potato", 10), 15, 3, none, 0);
		add("shroom_vodka", "Грибная водка", names("Mushroom Vodka", "Glowing Mushroom Vodka"),
				map("potato", 10, "red_mushroom", 3, "brown_mushroom", 3), 18, 5, none, 0);
		add("gin", "Джин", names("Pale Gin", "Gin", "Old Tom Gin"),
				map("wheat", 9, "blue_flowers", 6, "apple", 1), 6, 2, none, 0);
		add("tequila", "Текила", names("Mezcal", "Tequila", "Tequila anejo"),
				map("cactus", 8), 15, 2, "Берёзовая бочка", 12);
		add("absinthe", "Абсент", names("Poor Absinthe", "Absinthe", "Strong Absinthe"),
				map("grass", 15), 3, 6, none, 0);
		add("gr_absinthe", "Зелёный абсент", names("Green Absinthe", "Bright Green Absinthe"),
				map("grass", 17, "poisonous_potato", 2), 5, 6, none, 0);
		add("potato_soup", "Картофельный суп", names("Potato soup"),
				map("potato", 5, "grass", 3), 3, 0, none, 0);
		add("coffee", "Кофе", names("Stale Coffee", "Coffee", "Strong Coffee"),
				map("cocoa_beans", 12, "milk_bucket", 2), 2, 0, none, 0);
		add("eggnog", "Эгг-ног", names("Egg Liquor", "Eggnog", "Advocaat"),
				map("egg", 5, "sugar", 2, "milk_bucket", 1), 2, 0, any, 3);
		add("g_vodka", "Золотая водка", names("Rancid Vodka", "Golden Vodka", "Shimmering Golden Vodka"),
				map("potato", 10, "gold_nugget", 2), 18, 3, none, 0);
		add("fire_whiskey", "Огненный виски", names("Powdery Whiskey", "Burning Whiskey", "Blazing Whiskey"),
				map("wheat", 10, "blaze_powder", 2), 12, 3, "Еловая бочка", 18);
		add("hot_choc", "Горячий шоколад", names("Hot Chocolate"),
				map("cookie", 3), 2, 0, none, 0);
		add("iced_coffee", "Кофе со льдом", names("Watery Coffee", "Iced Coffee", "Strong Iced Coffee"),
				map("cookie", 8, "snowball", 4, "milk_bucket", 1), 1, 0, none, 0);
	}

	private Recipes() {
	}

	// ---------- Подбор рецепта по ингредиентам котла ----------

	/** Результат сравнения содержимого котла с таблицей рецептов. */
	public record Match(Recipe best, List<Recipe> ties, List<Recipe> candidates, List<String> hints, boolean unknownIngredient) {

		public boolean exact() {
			return best != null;
		}

		/** Короткое название для строки таймера. */
		public String title() {
			if (best != null) {
				if (ties.size() > 1) {
					List<String> names = new ArrayList<>();
					for (Recipe r : ties) {
						names.add(r.name());
					}
					return String.join(" / ", names);
				}
				return best.name();
			}
			return "Котёл";
		}
	}

	/** Ключ ингредиента для предмета; для предметов вне рецептов — «other:<id>». */
	public static String keyOf(ItemStack stack) {
		for (Ingredient ingredient : INGREDIENTS.values()) {
			for (Item item : ingredient.items()) {
				if (stack.is(item)) {
					return ingredient.key();
				}
			}
		}
		return "other:" + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	public static String nameOf(String key) {
		Ingredient ingredient = INGREDIENTS.get(key);
		if (ingredient != null) {
			return ingredient.name();
		}
		return key.startsWith("other:") ? key.substring("other:".length()) : key;
	}

	public static Match match(Map<String, Integer> allContents) {
		// Предметы не из рецептов в подборе не участвуют, но о них предупреждаем.
		Map<String, Integer> contents = new LinkedHashMap<>();
		List<String> foreign = new ArrayList<>();
		for (Map.Entry<String, Integer> e : allContents.entrySet()) {
			if (INGREDIENTS.containsKey(e.getKey())) {
				contents.put(e.getKey(), e.getValue());
			} else {
				foreign.add(nameOf(e.getKey()) + " ×" + e.getValue());
			}
		}
		boolean unknown = !foreign.isEmpty();
		String foreignHint = "посторонний предмет: " + String.join(", ", foreign) + " (если это не ингредиент — не страшно)";

		List<Recipe> candidates = new ArrayList<>();
		List<Recipe> exact = new ArrayList<>();
		for (Recipe recipe : RECIPES) {
			if (recipe.ingredients().keySet().containsAll(contents.keySet())) {
				candidates.add(recipe);
				if (recipe.ingredients().keySet().equals(contents.keySet())) {
					exact.add(recipe);
				}
			}
		}

		if (exact.isEmpty()) {
			List<String> hints = new ArrayList<>();
			if (contents.isEmpty()) {
				hints.add("пока нет ингредиентов из рецептов");
			} else if (candidates.isEmpty()) {
				hints.add("нет подходящих рецептов");
			} else {
				hints.add("подходят: " + joinNames(candidates, 5));
			}
			if (unknown) {
				hints.add(foreignHint);
			}
			return new Match(null, List.of(), candidates, hints, unknown);
		}

		// Среди рецептов с тем же набором ингредиентов берём ближайший по количеству.
		int bestDistance = Integer.MAX_VALUE;
		List<Recipe> ties = new ArrayList<>();
		for (Recipe recipe : exact) {
			int distance = 0;
			for (Map.Entry<String, Integer> e : recipe.ingredients().entrySet()) {
				distance += Math.abs(e.getValue() - contents.getOrDefault(e.getKey(), 0));
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				ties.clear();
				ties.add(recipe);
			} else if (distance == bestDistance) {
				ties.add(recipe);
			}
		}

		Recipe best = ties.get(0);
		List<String> hints = new ArrayList<>();
		for (Map.Entry<String, Integer> e : best.ingredients().entrySet()) {
			int have = contents.getOrDefault(e.getKey(), 0);
			int diff = e.getValue() - have;
			if (diff > 0) {
				hints.add("не хватает: " + nameOf(e.getKey()) + " ×" + diff);
			} else if (diff < 0) {
				hints.add("лишнее: " + nameOf(e.getKey()) + " ×" + (-diff));
			}
		}
		if (ties.size() > 1) {
			hints.add("отличаются бочкой: " + joinWoods(ties));
		}
		if (unknown) {
			hints.add(foreignHint);
		}
		return new Match(best, ties, candidates, hints, unknown);
	}

	// ---------- Определение напитка в бочке по названию ----------

	/** Рецепт с выдержкой, которому соответствует название напитка, или null. */
	public static Recipe byDrinkName(String drinkName) {
		String name = normalize(drinkName);
		for (Recipe recipe : RECIPES) {
			if (recipe.ageYears() <= 0) {
				continue;
			}
			if (normalize(recipe.name()).equals(name)) {
				return recipe;
			}
			for (String serverName : recipe.serverNames()) {
				if (normalize(serverName).equals(name)) {
					return recipe;
				}
			}
		}
		return null;
	}

	public static Recipe byId(String id) {
		for (Recipe recipe : RECIPES) {
			if (recipe.id().equals(id)) {
				return recipe;
			}
		}
		return null;
	}

	// ---------- Форматирование ----------

	/** 1:05 для минут и секунд, 2ч 40мин для долгих сроков. */
	public static String formatDuration(long millis) {
		long minutes = Math.max(0, millis) / 60_000L;
		long hours = minutes / 60;
		long rest = minutes % 60;
		if (hours == 0) {
			return rest + "мин";
		}
		return rest == 0 ? hours + "ч" : hours + "ч " + rest + "мин";
	}

	/** Таймерный вид: 4:07 или 1:02:15. */
	public static String formatClock(long millis) {
		long total = Math.max(0, millis) / 1000L;
		long h = total / 3600;
		long m = (total % 3600) / 60;
		long s = total % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

	public static String plural(long n, String one, String few, String many) {
		long mod100 = n % 100;
		long mod10 = n % 10;
		if (mod100 >= 11 && mod100 <= 14) {
			return many;
		}
		if (mod10 == 1) {
			return one;
		}
		if (mod10 >= 2 && mod10 <= 4) {
			return few;
		}
		return many;
	}

	private static String normalize(String s) {
		// Убираем коды форматирования (§6, &6) и лишние пробелы.
		return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim().toLowerCase(Locale.ROOT);
	}

	private static String joinNames(List<Recipe> recipes, int limit) {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < recipes.size() && i < limit; i++) {
			names.add(recipes.get(i).name());
		}
		String result = String.join(", ", names);
		return recipes.size() > limit ? result + " и ещё " + (recipes.size() - limit) : result;
	}

	private static String joinWoods(List<Recipe> recipes) {
		List<String> parts = new ArrayList<>();
		for (Recipe r : recipes) {
			parts.add(r.name() + " — " + r.wood().toLowerCase(Locale.ROOT) + ", " + r.ageYears() + " "
					+ plural(r.ageYears(), "год", "года", "лет"));
		}
		return String.join("; ", parts);
	}

	// ---------- Построение таблицы ----------

	private static void ing(String key, String name, Item... items) {
		INGREDIENTS.put(key, new Ingredient(key, name, Set.of(items)));
	}

	private static void add(String id, String name, List<String> serverNames, Map<String, Integer> ingredients,
			int cook, int distill, String wood, int age) {
		RECIPES.add(new Recipe(id, name, serverNames, ingredients, cook, distill, wood, age));
	}

	private static List<String> names(String... names) {
		return List.of(names);
	}

	private static Map<String, Integer> map(Object... pairs) {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			result.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return result;
	}
}

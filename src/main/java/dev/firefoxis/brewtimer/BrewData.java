package dev.firefoxis.brewtimer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Чтение состояния напитка BreweryX с клиента.
 *
 * 1. Скрытый тег: BreweryX хранит напиток в custom_data → PublicBukkitValues → "breweryx:brewdata"
 *    (массив байт). Если на сервере выключено шифрование (enableEncode: false — по умолчанию),
 *    там открыто лежат качество, дистилляции, выдержка в годах (дробная), дерево и рецепт.
 * 2. Описание: строка вида «15 лет Настоявшееся в бочке» — только целые годы.
 */
public final class BrewData {
	/** Откуда взяты данные. */
	public enum Source {
		TAG, LORE, NONE
	}

	/** Расшифрованный тег BreweryX. */
	public record Tag(int quality, int distillRuns, float ageYears, float wood, String recipe, int alcohol) {
	}

	/** Итог чтения напитка. */
	public record Result(Source source, Tag tag, Integer loreYears, boolean tagFound, boolean tagEncrypted) {

		/** Возраст в годах: дробный из тега, целый из описания или null. */
		public Float ageYears() {
			if (tag != null) {
				return tag.ageYears();
			}
			return loreYears == null ? null : loreYears.floatValue();
		}
	}

	private static final Pattern BREWDATA = Pattern.compile("\"?[a-z0-9_.-]+:brewdata\"?\\s*:\\s*\\[B;([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern LORE_YEARS = Pattern.compile("(\\d+)\\s*(лет|года|год|years?)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private BrewData() {
	}

	public static Result read(ItemStack stack) {
		boolean found = false;
		boolean encrypted = false;
		Tag tag = null;

		byte[] bytes = rawTag(stack);
		if (bytes != null) {
			found = true;
			try {
				tag = parse(bytes);
			} catch (EncryptedException e) {
				encrypted = true;
			} catch (IOException | RuntimeException e) {
				BrewTimerClient.LOGGER.debug("Не удалось разобрать brewdata", e);
			}
		}

		Integer loreYears = loreYears(stack);
		Source source = tag != null ? Source.TAG : loreYears != null ? Source.LORE : Source.NONE;
		return new Result(source, tag, loreYears, found, encrypted);
	}

	/** Сырые байты тега brewdata или null. */
	public static byte[] rawTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		// Разбираем текстовое представление NBT: так не зависим от методов CompoundTag в конкретной версии.
		Matcher m = BREWDATA.matcher(data.toString());
		if (!m.find()) {
			return null;
		}
		String body = m.group(1).trim();
		if (body.isEmpty()) {
			return new byte[0];
		}
		String[] parts = body.split(",");
		byte[] bytes = new byte[parts.length];
		for (int i = 0; i < parts.length; i++) {
			String p = parts[i].trim();
			if (p.endsWith("b") || p.endsWith("B")) {
				p = p.substring(0, p.length() - 1);
			}
			bytes[i] = (byte) Integer.parseInt(p);
		}
		return bytes;
	}

	private static final class EncryptedException extends IOException {
		EncryptedException() {
			super("brewdata зашифрован ключом сервера");
		}
	}

	/** Формат BreweryX (Brew.save / saveToStream, версия данных 1). */
	private static Tag parse(byte[] bytes) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readByte() != 86) {
				throw new IOException("нет метки 86");
			}
			byte version = in.readByte();
			if (version != 1) {
				throw new IOException("неизвестная версия данных " + version);
			}
			// id сида шифрования: 0 — данные не зашифрованы.
			short seedId = in.readShort();
			if (seedId != 0) {
				throw new EncryptedException();
			}

			int quality = in.readByte();
			int bools = in.readUnsignedByte();
			int alcohol = (bools & 64) != 0 ? in.readShort() : 0;
			int distill = (bools & 1) != 0 ? in.readByte() : 0;
			float age = (bools & 2) != 0 ? in.readFloat() : 0F;
			float wood = (bools & 4) != 0 ? in.readFloat() : -1F;
			String recipe = (bools & 8) != 0 ? in.readUTF() : null;

			if (quality < 0 || quality > 10 || age < 0 || age > 100_000 || Float.isNaN(age)) {
				throw new IOException("неправдоподобные значения");
			}
			return new Tag(quality, distill, age, wood, recipe, alcohol);
		}
	}

	/** Целое число лет из описания напитка или null. */
	public static Integer loreYears(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return null;
		}
		for (Component line : lore.lines()) {
			String text = line.getString().replaceAll("§.", "");
			String lower = text.toLowerCase();
			// Строка выдержки: «… в бочке» / «… barrel …».
			if (!lower.contains("бочк") && !lower.contains("barrel")) {
				continue;
			}
			Matcher m = LORE_YEARS.matcher(text);
			if (m.find()) {
				return Integer.parseInt(m.group(1));
			}
		}
		return null;
	}

	/** Отчёт для отладочной клавиши: что удалось прочитать. */
	public static List<String> describe(ItemStack stack) {
		List<String> lines = new ArrayList<>();
		lines.add("Предмет: " + stack.getHoverName().getString());
		Result r = read(stack);
		if (r.tag() != null) {
			Tag t = r.tag();
			lines.add("Тег BreweryX: прочитан (без шифрования)");
			lines.add(String.format("  рецепт: %s, качество: %d/10, дистилляций: %d", t.recipe(), t.quality(), t.distillRuns()));
			lines.add(String.format("  выдержка: %.2f лет (%s), дерево: %s, алкоголь: %d",
					t.ageYears(), Recipes.formatDuration((long) (t.ageYears() * Recipes.MINUTES_PER_YEAR * 60_000L)),
					t.wood() < 0 ? "нет" : String.valueOf(t.wood()), t.alcohol()));
		} else if (r.tagEncrypted()) {
			lines.add("Тег BreweryX: есть, но зашифрован ключом сервера (enableEncode)");
		} else if (r.tagFound()) {
			lines.add("Тег BreweryX: есть, но формат не распознан");
		} else {
			lines.add("Тег BreweryX: не найден (сервер не передаёт или предмет не напиток)");
		}
		lines.add("Годы в описании: " + (r.loreYears() == null ? "не найдены" : r.loreYears()));
		return lines;
	}
}

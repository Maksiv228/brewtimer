package dev.firefoxis.brewtimer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Окно со всеми таймерами — открывается клавишей (по умолчанию B). */
public class TimersScreen extends Screen {
	private static final int ROW_GAP = 6;
	private static final int COLOR_TITLE = 0xFFFFAA00;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DETAIL = 0xFFAAAAAA;
	private static final int COLOR_SOON = 0xFFFFFF55;
	private static final int COLOR_LATE = 0xFFFF5555;
	private static final int COLOR_OK = 0xFF55FF55;
	private static final int COLOR_PANEL = 0xC0101010;

	private final int page;
	private int pageCount = 1;
	private int left;
	private int panelWidth;
	/** id таймера → верхняя координата его строки на текущей странице. */
	private final List<String> shownIds = new ArrayList<>();
	private final Map<String, Integer> rowTop = new HashMap<>();
	private List<String> allIdsAtInit = List.of();

	public TimersScreen() {
		this(0);
	}

	private TimersScreen(int page) {
		super(Component.literal("Таймеры варки"));
		this.page = page;
	}

	private String dimension() {
		return this.minecraft.level == null ? "" : BrewTimerClient.dimensionOf(this.minecraft.level);
	}

	private int rowHeight(TimerStore.View view) {
		return 20 + view.details().size() * (this.font.lineHeight + 1) + ROW_GAP;
	}

	@Override
	protected void init() {
		this.panelWidth = Math.min(this.width - 20, 440);
		this.left = (this.width - this.panelWidth) / 2;

		List<TimerStore.View> views = TimerStore.views(dimension());
		List<String> ids = new ArrayList<>();
		for (TimerStore.View view : views) {
			ids.add(view.id());
		}
		this.allIdsAtInit = ids;

		// Разбиваем на страницы по высоте экрана.
		int top = 34;
		int bottomLimit = this.height - 34;
		List<List<TimerStore.View>> pages = new ArrayList<>();
		List<TimerStore.View> current = new ArrayList<>();
		int y = top;
		for (TimerStore.View view : views) {
			int h = rowHeight(view);
			if (!current.isEmpty() && y + h > bottomLimit) {
				pages.add(current);
				current = new ArrayList<>();
				y = top;
			}
			current.add(view);
			y += h;
		}
		if (!current.isEmpty()) {
			pages.add(current);
		}
		this.pageCount = Math.max(1, pages.size());
		int shownPage = Math.min(this.page, this.pageCount - 1);

		this.shownIds.clear();
		this.rowTop.clear();
		if (!pages.isEmpty()) {
			y = top;
			for (TimerStore.View view : pages.get(shownPage)) {
				this.shownIds.add(view.id());
				this.rowTop.put(view.id(), y);
				Runnable remove = view.remove();
				String id = view.id();
				this.addRenderableWidget(Button.builder(Component.literal("✕"), btn -> {
					remove.run();
					BrewTimerClient.forgetWarnings(id);
					this.minecraft.gui.setScreen(new TimersScreen(shownPage));
				}).bounds(this.left + this.panelWidth - 24, y, 20, 20).build());
				if (view.canPickRecipe()) {
					Runnable prev = view.prevRecipe();
					Runnable next = view.nextRecipe();
					this.addRenderableWidget(Button.builder(Component.literal("◀"), btn -> prev.run())
							.bounds(this.left + this.panelWidth - 70, y, 20, 20).build());
					this.addRenderableWidget(Button.builder(Component.literal("▶"), btn -> next.run())
							.bounds(this.left + this.panelWidth - 48, y, 20, 20).build());
				}
				y += rowHeight(view);
			}
		}

		int buttonsY = this.height - 26;
		int center = this.width / 2;
		if (this.pageCount > 1) {
			this.addRenderableWidget(Button.builder(Component.literal("◀"), btn ->
					this.minecraft.gui.setScreen(new TimersScreen(Math.max(0, shownPage - 1))))
					.bounds(center - 110, buttonsY, 20, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("▶"), btn ->
					this.minecraft.gui.setScreen(new TimersScreen(Math.min(this.pageCount - 1, shownPage + 1))))
					.bounds(center + 90, buttonsY, 20, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.literal("Закрыть"), btn -> this.onClose())
				.bounds(center - 50, buttonsY, 100, 20).build());
	}

	@Override
	public void tick() {
		super.tick();
		// Таймер появился или пропал — перестраиваем окно.
		List<String> ids = new ArrayList<>();
		for (TimerStore.View view : TimerStore.views(dimension())) {
			ids.add(view.id());
		}
		if (!ids.equals(this.allIdsAtInit)) {
			this.minecraft.gui.setScreen(new TimersScreen(this.page));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String header = "Таймеры варки и выдержки" + (this.pageCount > 1 ? "  (" + (Math.min(this.page, this.pageCount - 1) + 1) + "/" + this.pageCount + ")" : "");
		graphics.centeredText(this.font, header, this.width / 2, 12, COLOR_TITLE);

		List<TimerStore.View> views = TimerStore.views(dimension());
		if (views.isEmpty()) {
			graphics.centeredText(this.font, "Сейчас ничего не варится и не выдерживается", this.width / 2, this.height / 2 - 10, COLOR_DETAIL);
			graphics.centeredText(this.font, "Кинь ингредиент в котёл с водой или положи напиток в бочку", this.width / 2, this.height / 2 + 4, COLOR_DETAIL);
			return;
		}

		for (TimerStore.View view : views) {
			Integer top = this.rowTop.get(view.id());
			if (top == null) {
				continue;
			}
			int height = rowHeight(view) - ROW_GAP;
			graphics.fill(this.left, top, this.left + this.panelWidth, top + height, COLOR_PANEL);

			int textLeft = this.left + 6;
			int timeRight = this.left + this.panelWidth - (view.canPickRecipe() ? 76 : 30);

			String time = BrewTimerClient.timeText(view);
			int timeColor = !view.known() ? COLOR_DETAIL
					: view.remaining() <= 0 ? COLOR_LATE
					: view.remaining() <= 60_000L ? COLOR_SOON
					: COLOR_OK;
			graphics.text(this.font, time, timeRight - this.font.width(time), top + 6, timeColor, true);

			String title = view.kind() + ": " + view.title() + "  [" + view.location() + "]";
			int titleMax = timeRight - this.font.width(time) - 10 - textLeft;
			graphics.text(this.font, fit(title, titleMax), textLeft, top + 6, COLOR_TEXT, true);

			int y = top + 20;
			for (String detail : view.details()) {
				graphics.text(this.font, fit(detail, this.panelWidth - 12), textLeft, y, COLOR_DETAIL, false);
				y += this.font.lineHeight + 1;
			}
		}
	}

	/** Обрезает строку под ширину, добавляя многоточие. */
	private String fit(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int end = text.length();
		while (end > 0 && this.font.width(text.substring(0, end) + ellipsis) > maxWidth) {
			end--;
		}
		return text.substring(0, end) + ellipsis;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

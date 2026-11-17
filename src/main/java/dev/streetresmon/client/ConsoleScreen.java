package dev.streetresmon.client;

import dev.streetresmon.command.CommandContext;
import dev.streetresmon.command.CommandRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * FiveM-F8-style console: full width at the top, menu bar, colored log with chips, scrollbar,
 * input line. The game stays visible behind it. Input goes through a real EditBox widget.
 */
public class ConsoleScreen extends Screen {
    private static final int MENU_H = 13;
    private static final int LINE_H = 10;
    private static final int INPUT_H = 14;
    private static final int SCROLLBAR_W = 6;

    private static final int COL_BG = 0xCC0E1014;
    private static final int COL_MENU = 0xE6171A1F;
    private static final int COL_BORDER = 0xFF2F7FB5;
    private static final int COL_SEP = 0xFF3A3F46;
    private static final int COL_INPUT_BG = 0xCC1B1E24;
    private static final int COL_TEXT = 0xFFE8EAED;

    private record MenuItem(String label, String action) {}

    private record Menu(String label, List<MenuItem> items) {}

    private static final String ACTION_CLOSE = "::close";

    private static final List<Menu> MENUS = List.of(
            new Menu("Overlays", List.of(new MenuItem("Show Resource Monitor  (resmon 1)", "resmon 1"), new MenuItem("Hide Resource Monitor  (resmon 0)", "resmon 0"))),
            new Menu("Launch", List.of(new MenuItem("Show version", "version"), new MenuItem("Profiler start", "profiler start"), new MenuItem("Profiler stop", "profiler stop"))),
            new Menu("Quit", List.of(new MenuItem("Close console", ACTION_CLOSE))),
            new Menu("Tools", List.of(new MenuItem("Help", "help"), new MenuItem("Clear output", "clear"), new MenuItem("Memory", "memory"), new MenuItem("Crash analyzer", "crashlog"))),
            new Menu("Game", List.of(new MenuItem("FPS", "fps"), new MenuItem("Loaded mods", "mods"))));

    /** Command history survives re-opening the console. */
    private static final List<String> HISTORY = new ArrayList<>();
    private static final int MAX_HISTORY = 100;

    private EditBox input;
    private int scroll;            // lines scrolled up from the bottom
    private boolean draggingBar;
    private int openMenu = -1;
    private int historyIndex = -1; // -1 = editing a fresh line
    private String draft = "";

    private final CommandContext context = new CommandContext() {
        @Override public void print(String text) { ConsoleLog.info(text); }
        @Override public void warn(String text) { ConsoleLog.warn(text); }
        @Override public void error(String text) { ConsoleLog.error(text); }
    };

    public ConsoleScreen() {
        super(Component.literal("StreetResmon Console"));
        ConsoleLog.welcomeOnce();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int consoleHeight() {
        return Mth.clamp(height * 2 / 5, Math.min(height, 120), height);
    }

    @Override
    protected void init() {
        ResmonWindow.setScreenSize(width, height);
        int ch = consoleHeight();
        input = new EditBox(font, 16, ch - INPUT_H - 3, Math.max(60, width - 16 - 14), INPUT_H, Component.literal("Command"));
        input.setBordered(false);
        input.setMaxLength(512);
        input.setHint(Component.literal("Type a command and press Enter..."));
        addRenderableWidget(input);
        setInitialFocus(input);
        input.setFocused(true);
    }

    // ---------- geometry helpers ----------

    private int logTop() { return MENU_H + 4; }

    private int logBottom() { return consoleHeight() - INPUT_H - 8; }

    private int visibleLines() { return Math.max(1, (logBottom() - logTop()) / LINE_H); }

    private int maxScroll() { return Math.max(0, ConsoleLog.size() - visibleLines()); }

    private int menuX(int index) {
        int x = 6;
        for (int i = 0; i < index; i++) x += font.width(MENUS.get(i).label()) + 12;
        return x;
    }

    private int menuW(int index) { return font.width(MENUS.get(index).label()) + 8; }

    private int dropdownWidth(Menu m) {
        int w = 0;
        for (MenuItem it : m.items()) w = Math.max(w, font.width(it.label()));
        return w + 14;
    }

    // ---------- rendering ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        int w = width;
        int ch = consoleHeight();

        g.fill(0, 0, w, ch, COL_BG);
        g.fill(0, 0, w, MENU_H, COL_MENU);
        g.fill(0, MENU_H, w, MENU_H + 1, COL_SEP);

        for (int i = 0; i < MENUS.size(); i++) {
            int x = menuX(i);
            if (i == openMenu) g.fill(x - 4, 0, x - 4 + menuW(i), MENU_H, 0xFF2A3440);
            g.text(font, MENUS.get(i).label(), x, 3, COL_TEXT, false);
        }

        drawLog(g);

        // input line
        int inputTop = ch - INPUT_H - 5;
        g.fill(0, inputTop - 3, w, inputTop - 2, COL_SEP);
        g.fill(4, inputTop - 1, w - 4, ch - 3, COL_INPUT_BG);
        g.text(font, ">", 7, inputTop + (INPUT_H - 8) / 2, 0xFF5CB0FF, false);

        // thin blue accent outline (drawn over the panel edge)
        g.fill(0, 0, w, 1, COL_BORDER);
        g.fill(0, ch - 1, w, ch, COL_BORDER);
        g.fill(0, 0, 1, ch, COL_BORDER);
        g.fill(w - 1, 0, w, ch, COL_BORDER);

        super.extractRenderState(g, mx, my, partial); // renders the EditBox (text + caret)

        ResmonWindow.draw(g, font); // on top of the console so it can be grabbed anywhere

        drawDropdown(g, mx, my);
    }

    private void drawLog(GuiGraphicsExtractor g) {
        int total = ConsoleLog.size();
        int visible = visibleLines();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        int last = total - scroll;              // exclusive
        int first = Math.max(0, last - visible);
        List<ConsoleLog.Line> lines = ConsoleLog.slice(first, last);

        int maxX = width - SCROLLBAR_W - 8;
        int y = logTop();
        for (ConsoleLog.Line line : lines) {
            int x = 8;
            if (line.tag() != null) {
                int cw = font.width(line.tag()) + 4;
                g.fill(x, y - 1, x + cw, y + LINE_H - 1, line.tagColor());
                g.text(font, line.tag(), x + 2, y, 0xFFFFFFFF, false);
                x += cw + 5;
            }
            ColorText.draw(g, font, line.text(), x, y, COL_TEXT, maxX);
            y += LINE_H;
        }

        // scrollbar
        int trackTop = logTop();
        int trackH = logBottom() - trackTop;
        int tx = width - SCROLLBAR_W - 3;
        g.fill(tx, trackTop, tx + SCROLLBAR_W, trackTop + trackH, 0x33FFFFFF);
        int ms = maxScroll();
        int thumbH = ms == 0 ? trackH : Math.max(14, trackH * visible / Math.max(total, 1));
        int thumbY = ms == 0 ? trackTop : trackTop + Math.round((trackH - thumbH) * (1f - (float) scroll / ms));
        g.fill(tx, thumbY, tx + SCROLLBAR_W, thumbY + thumbH, draggingBar ? 0xFF7FC4EE : 0xFF4A8DB8);
    }

    private void drawDropdown(GuiGraphicsExtractor g, int mx, int my) {
        if (openMenu < 0) return;
        Menu m = MENUS.get(openMenu);
        int x = menuX(openMenu) - 4;
        int w = dropdownWidth(m);
        int h = m.items().size() * 12 + 4;
        g.fill(x, MENU_H + 1, x + w, MENU_H + 1 + h, 0xF01B2028);
        g.outline(x, MENU_H + 1, w, h, COL_BORDER);
        for (int i = 0; i < m.items().size(); i++) {
            int iy = MENU_H + 3 + i * 12;
            boolean hover = mx >= x && mx < x + w && my >= iy && my < iy + 12;
            if (hover) g.fill(x + 1, iy, x + w - 1, iy + 12, 0xFF2A3F52);
            g.text(font, m.items().get(i).label(), x + 6, iy + 2, COL_TEXT, false);
        }
    }

    // ---------- input ----------

    private void submit() {
        String line = input.getValue().trim();
        if (line.isEmpty()) return;
        input.setValue("");
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(line)) {
            HISTORY.add(line);
            if (HISTORY.size() > MAX_HISTORY) HISTORY.remove(0);
        }
        historyIndex = -1;
        draft = "";
        scroll = 0;
        ConsoleLog.echo(line);
        CommandRegistry.execute(line, context);
    }

    private void runMenuAction(String action) {
        if (ACTION_CLOSE.equals(action)) {
            onClose();
            return;
        }
        scroll = 0;
        ConsoleLog.echo(action);
        CommandRegistry.execute(action, context);
    }

    private void browseHistory(int delta) {
        if (HISTORY.isEmpty()) return;
        if (historyIndex == -1) {
            if (delta > 0) return;
            draft = input.getValue();
            historyIndex = HISTORY.size() - 1;
        } else {
            historyIndex += delta;
            if (historyIndex >= HISTORY.size()) {
                historyIndex = -1;
                input.setValue(draft);
                return;
            }
            historyIndex = Math.max(0, historyIndex);
        }
        input.setValue(HISTORY.get(historyIndex));
    }

    private void focusInput() {
        setFocused(input);
        input.setFocused(true);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (Keys.isEscape(event) || Keys.isF8(event)) {
            if (openMenu >= 0 && Keys.isEscape(event)) {
                openMenu = -1;
                return true;
            }
            onClose();
            return true;
        }
        if (Keys.isEnter(event)) {
            submit();
            focusInput();
            return true;
        }
        if (Keys.isUp(event)) { browseHistory(-1); return true; }
        if (Keys.isDown(event)) { browseHistory(1); return true; }
        if (Keys.isPageUp(event)) { scroll = Mth.clamp(scroll + visibleLines() - 1, 0, maxScroll()); return true; }
        if (Keys.isPageDown(event)) { scroll = Mth.clamp(scroll - visibleLines() + 1, 0, maxScroll()); return true; }
        // everything else (Backspace, Delete, Left/Right, Home/End, Ctrl+V ...) goes to the EditBox
        return input.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return input.charTyped(event) || super.charTyped(event);
    }

    // ---------- mouse ----------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (Keys.isLeftButton(event.button())) {
            // open dropdown: click on an item
            if (openMenu >= 0) {
                Menu m = MENUS.get(openMenu);
                int x = menuX(openMenu) - 4;
                int w = dropdownWidth(m);
                for (int i = 0; i < m.items().size(); i++) {
                    int iy = MENU_H + 3 + i * 12;
                    if (mx >= x && mx < x + w && my >= iy && my < iy + 12) {
                        String action = m.items().get(i).action();
                        openMenu = -1;
                        runMenuAction(action);
                        return true;
                    }
                }
            }
            // resource monitor window (drawn on top of the console)
            if (ResmonWindow.mouseClicked(mx, my, event.button())) {
                openMenu = -1;
                return true;
            }
            // menu bar
            if (my >= 0 && my < MENU_H) {
                for (int i = 0; i < MENUS.size(); i++) {
                    int x = menuX(i) - 4;
                    if (mx >= x && mx < x + menuW(i)) {
                        openMenu = openMenu == i ? -1 : i;
                        return true;
                    }
                }
                openMenu = -1;
                return true;
            }
            openMenu = -1;
            // scrollbar
            if (mx >= width - SCROLLBAR_W - 5 && my >= logTop() && my <= logBottom()) {
                draggingBar = true;
                scrollFromMouse(my);
                return true;
            }
        }
        // clicking anywhere in the input row focuses the text field
        if (my >= logBottom() && my <= consoleHeight()) {
            focusInput();
            if (input.mouseClicked(event, doubleClick)) return true;
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        return my <= consoleHeight(); // swallow clicks on the console panel
    }

    private void scrollFromMouse(double my) {
        int trackTop = logTop();
        int trackH = logBottom() - trackTop;
        int total = ConsoleLog.size();
        int thumbH = Math.max(14, trackH * visibleLines() / Math.max(total, 1));
        double frac = (my - trackTop - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        frac = Mth.clamp(frac, 0.0, 1.0);
        scroll = Math.round((float) ((1.0 - frac) * maxScroll()));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (ResmonWindow.mouseDragged(event.x(), event.y())) return true;
        if (draggingBar) {
            scrollFromMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false;
        ResmonWindow.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ResmonWindow.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (mouseY <= consoleHeight()) {
            scroll = Mth.clamp(scroll + (int) Math.signum(scrollY) * 3, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}

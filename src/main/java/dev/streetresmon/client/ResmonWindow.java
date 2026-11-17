package dev.streetresmon.client;

import dev.streetresmon.core.resmon.ModSampleTable;
import dev.streetresmon.perf.PerformanceMonitor;
import dev.streetresmon.perf.SamplerHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * The resource monitor window. It is a HUD overlay (NOT a Screen), so the player can keep playing
 * while it is visible. It is opened with "resmon 1" and closed ONLY with "resmon 0".
 * While the console is open the window can be dragged (title bar) and resized (bottom-right corner).
 */
public final class ResmonWindow {
    private static final int MIN_W = 320, MIN_H = 190;
    private static final int TITLE_H = 16, TAB_H = 14, FOOT_H = 26, ROW_H = 11, HEAD_H = 14, NOTE_H = 12;

    private static final int COL_BODY = 0xE60F1217;
    private static final int COL_TITLE = 0xF0151B23;
    private static final int COL_ACCENT = 0xFF2F7FB5;
    private static final int COL_SEP = 0xFF3A3F46;
    private static final int COL_TEXT = 0xFFE8EAED;
    private static final int COL_DIM = 0xFF8A929B;

    private static final String[] TABS = {"Resources", "Server", "Threads"};
    private static final int TAB_CLIENT = 0, TAB_SERVER = 1, TAB_THREADS = 2;
    private static final long ROWS_REFRESH_NS = 500_000_000L;

    /** One table row. Values are ESTIMATES from stack sampling (see ModSampleTable). */
    private record Row(String id, double selfMs, double selfPct, double inclMs, boolean isMod) {}

    private static final List<List<Row>> rowCache = new ArrayList<>(List.of(List.of(), List.of()));
    private static final long[] rowCacheAt = new long[2];
    private static int sortCol = 1; // 0 name, 1 CPU ms/s, 2 time %, 3 inclusive ms/s
    private static boolean sortDesc = true;

    private static boolean visible;
    private static int px, py, pw = 560, ph = 360;
    private static boolean placed;
    private static int tab;
    private static int scroll;
    private static int screenW, screenH;
    private static boolean dragging, resizing;
    private static int dragDX, dragDY;
    private static List<IModInfo> mods = List.of();

    private ResmonWindow() {}

    // ---------- state ----------

    public static boolean isVisible() {
        return visible;
    }

    /** @return true if the visibility changed */
    public static boolean setVisible(boolean v) {
        if (visible == v) return false;
        visible = v;
        if (v) {
            mods = ModList.get().getMods().stream().sorted(Comparator.comparing(IModInfo::getModId)).toList();
            if (!placed) place();
            clampWindow();
            rowCacheAt[0] = 0;
            rowCacheAt[1] = 0;
            SamplerHub.acquire("resmon");
        } else {
            dragging = false;
            resizing = false;
            SamplerHub.release("resmon");
        }
        return true;
    }

    /** Called by the console whenever it is (re)initialised so clamping uses the real GUI size. */
    public static void setScreenSize(int w, int h) {
        screenW = w;
        screenH = h;
        if (visible && !placed) place();
        clampWindow();
    }

    private static void place() {
        if (screenW <= 0 || screenH <= 0) return;
        pw = Math.min(560, screenW - 20);
        int top = screenH * 2 / 5 + 8; // below the console panel
        ph = Math.max(Math.min(MIN_H, screenH), Math.min(360, screenH - top - 8));
        px = (screenW - pw) / 2;
        py = top;
        placed = true;
    }

    /** Keeps the whole window inside the screen with a sane minimum size. */
    private static void clampWindow() {
        if (screenW <= 0 || screenH <= 0) return;
        pw = Mth.clamp(pw, Math.min(MIN_W, screenW), screenW);
        ph = Mth.clamp(ph, Math.min(MIN_H, screenH), screenH);
        px = Mth.clamp(px, 0, screenW - pw);
        py = Mth.clamp(py, 0, screenH - ph);
    }

    // ---------- rendering ----------

    /** HUD layer entry point. Skipped while the console is open (the console draws it on top itself). */
    public static void drawHud(GuiGraphicsExtractor g) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof ConsoleScreen) return;
        draw(g, mc.font);
    }

    public static void draw(GuiGraphicsExtractor g, Font font) {
        if (!visible) return;
        PerformanceMonitor pm = PerformanceMonitor.get();
        pm.refresh();

        int x = px, y = py, w = pw, h = ph;
        g.fill(x, y, x + w, y + h, COL_BODY);
        g.fill(x, y, x + w, y + TITLE_H, COL_TITLE);
        g.fill(x, y + TITLE_H - 1, x + w, y + TITLE_H, COL_ACCENT);
        g.text(font, "Resource Monitor", x + 6, y + 4, COL_TEXT, false);
        g.text(font, "x", x + w - 11, y + 4, COL_TEXT, false); // close (click while the console is open)
        String hint = "F5 / resmon 0 / x";
        if (w > 260) g.text(font, hint, x + w - font.width(hint) - 20, y + 4, COL_DIM, false);

        drawTabs(g, font);

        int contentTop = y + TITLE_H + TAB_H;
        int contentBottom = y + h - FOOT_H;
        if (tab == TAB_THREADS) drawThreads(g, font, pm, contentTop, contentBottom);
        else drawResources(g, font, tab == TAB_SERVER ? 1 : 0, contentTop, contentBottom);

        drawFooter(g, font, pm, y + h - FOOT_H);

        g.outline(x, y, w, h, COL_ACCENT);
        // resize grip
        g.fill(x + w - 9, y + h - 3, x + w - 3, y + h - 2, COL_ACCENT);
        g.fill(x + w - 6, y + h - 6, x + w - 3, y + h - 5, COL_ACCENT);
        g.fill(x + w - 3, y + h - 9, x + w - 2, y + h - 2, COL_ACCENT);
    }

    private static void drawTabs(GuiGraphicsExtractor g, Font font) {
        int x = px + 6;
        int y = py + TITLE_H;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 14;
            boolean active = tab == i;
            if (active) g.fill(x, y + 1, x + tw, y + TAB_H, 0xFF1D2733);
            g.text(font, TABS[i], x + 7, y + 4, active ? COL_TEXT : COL_DIM, false);
            if (active) g.fill(x, y + TAB_H - 1, x + tw, y + TAB_H, COL_ACCENT);
            x += tw + 2;
        }
    }

    private static int tabAt(double mx, double my) {
        if (my < py + TITLE_H || my >= py + TITLE_H + TAB_H) return -1;
        Font font = Minecraft.getInstance().font;
        int x = px + 6;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 14;
            if (mx >= x && mx < x + tw) return i;
            x += tw + 2;
        }
        return -1;
    }

    private static int[] columns() {
        int inner = pw - 12;
        int c1 = px + 6;
        return new int[]{c1, c1 + inner * 34 / 100, c1 + inner * 50 / 100, c1 + inner * 64 / 100, c1 + inner * 84 / 100};
    }

    private static final String[] RES_HEADERS = {"Resource", "CPU ms/s ~", "Time % ~", "CPU incl. ms/s ~", "Memory"};

    /** Builds (at most every 500 ms) the rows of one thread table: all loaded mods plus every owner that was sampled. */
    private static List<Row> rowsFor(int which) {
        long now = System.nanoTime();
        if (rowCacheAt[which] != 0 && now - rowCacheAt[which] < ROWS_REFRESH_NS) return rowCache.get(which);
        ModSampleTable t = which == 0 ? SamplerHub.clientTable() : SamplerHub.serverTable();
        List<Row> out = new ArrayList<>();
        if (t != null) {
            Map<String, ModSampleTable.Row> byId = new HashMap<>();
            for (ModSampleTable.Row r : t.snapshot(now)) byId.put(r.ownerId(), r);
            Set<String> ids = new LinkedHashSet<>();
            for (IModInfo m : mods) ids.add(m.getModId());
            ids.addAll(byId.keySet());
            Set<String> modIds = new LinkedHashSet<>();
            for (IModInfo m : mods) modIds.add(m.getModId());
            for (String id : ids) {
                ModSampleTable.Row r = byId.get(id);
                out.add(r == null ? new Row(id, 0, 0, 0, modIds.contains(id))
                        : new Row(id, r.estSelfMsPerSec(), r.selfPercent(), r.estInclusiveMsPerSec(), modIds.contains(id)));
            }
            Comparator<Row> cmp = switch (sortCol) {
                case 0 -> Comparator.comparing((Row r) -> r.id().toLowerCase(Locale.ROOT));
                case 2 -> Comparator.comparingDouble(Row::selfPct);
                case 3 -> Comparator.comparingDouble(Row::inclMs);
                default -> Comparator.comparingDouble(Row::selfMs);
            };
            out.sort(sortDesc ? cmp.reversed() : cmp);
        }
        rowCache.set(which, out);
        rowCacheAt[which] = now;
        return out;
    }

    private static void drawResources(GuiGraphicsExtractor g, Font font, int which, int top, int bottom) {
        int[] cols = columns();
        int right = px + pw - 6;
        drawHeader(g, font, top, cols, RES_HEADERS, right);
        // sort marker on the active column
        String arrow = sortDesc ? "v" : "^";
        int limit = sortCol + 1 < cols.length ? cols[sortCol + 1] - 6 : right;
        int ax = Math.min(limit - 6, cols[sortCol] + font.width(RES_HEADERS[sortCol]) + 3);
        if (ax + 6 <= limit) g.text(font, arrow, ax, top + 3, COL_ACCENT, false);

        int rowsTop = top + HEAD_H;
        int rowsBottom = bottom - NOTE_H;
        ModSampleTable t = which == 0 ? SamplerHub.clientTable() : SamplerHub.serverTable();
        if (t == null) {
            cell(g, font, which == 0 ? "Waiting for the first client tick to find the render thread..."
                    : "No server thread in this JVM (remote server, or no world loaded).", cols[0], rowsTop + 2, right, COL_DIM);
        } else {
            List<Row> rows = rowsFor(which);
            int visibleRows = Math.max(1, (rowsBottom - rowsTop - ROW_H) / ROW_H);
            scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows));

            long busy = t.busySamples(), idle = t.idleSamples();
            double busyPct = busy + idle == 0 ? 0 : 100.0 * busy / (busy + idle);
            int ty = rowsTop;
            g.fill(px + 3, ty - 1, px + pw - 3, ty + ROW_H - 1, 0x2A2F7FB5);
            cell(g, font, "(thread busy)", cols[0], ty, cols[1] - 6, COL_TEXT);
            cell(g, font, String.format(Locale.ROOT, "~%.0f %% of samples", busyPct), cols[1], ty, cols[3] - 6, COL_DIM);
            cell(g, font, (busy + idle) + " samples", cols[3], ty, right, COL_DIM);

            for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
                Row r = rows.get(scroll + i);
                int ry = rowsTop + (i + 1) * ROW_H;
                if ((i & 1) == 1) g.fill(px + 3, ry - 1, px + pw - 3, ry + ROW_H - 1, 0x16FFFFFF);
                int color = r.selfPct() > 25 ? 0xFFFF5F5F : (r.selfPct() > 8 ? 0xFFFFD15C : 0xFF6BDC73);
                cell(g, font, r.id(), cols[0], ry, cols[1] - 6, r.isMod() ? COL_TEXT : COL_DIM);
                cell(g, font, String.format(Locale.ROOT, "%.2f ms", r.selfMs()), cols[1], ry, cols[2] - 6, color);
                cell(g, font, String.format(Locale.ROOT, "%.1f %%", r.selfPct()), cols[2], ry, cols[3] - 6, color);
                cell(g, font, String.format(Locale.ROOT, "%.2f ms", r.inclMs()), cols[3], ry, cols[4] - 6, COL_DIM);
                cell(g, font, "n/a", cols[4], ry, right, COL_DIM);
            }
            if (rows.size() > visibleRows) {
                int tx = px + pw - 5;
                int trackH = rowsBottom - rowsTop;
                int thumbH = Math.max(10, trackH * visibleRows / rows.size());
                int maxScroll = rows.size() - visibleRows;
                int thumbY = rowsTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll);
                g.fill(tx, thumbY, tx + 2, thumbY + thumbH, 0xFF4A8DB8);
            }
        }
        g.fill(px + 6, rowsBottom, px + pw - 6, rowsBottom + 1, COL_SEP);
        cell(g, font, "~ = estimate: stack sampling every " + SamplerHub.INTERVAL_MS + " ms; GPU/native time not included; memory per mod not measurable",
                cols[0], rowsBottom + 3, right, COL_DIM);
    }

    private static void drawThreads(GuiGraphicsExtractor g, Font font, PerformanceMonitor pm, int top, int bottom) {
        int inner = pw - 12;
        int c1 = px + 6;
        int c2 = c1 + inner * 70 / 100;
        int right = px + pw - 6;
        drawHeader(g, font, top, new int[]{c1, c2}, new String[]{"Thread", "CPU (% of one core)"}, right);

        int rowsTop = top + HEAD_H;
        if (!pm.threadStatsAvailable()) {
            cell(g, font, "Measuring... (needs two samples) or thread CPU time unsupported by this JVM", c1, rowsTop + 2, right, COL_DIM);
            return;
        }
        List<PerformanceMonitor.ThreadStat> stats = pm.threadStats();
        int visibleRows = Math.max(1, (bottom - rowsTop) / ROW_H);
        scroll = Mth.clamp(scroll, 0, Math.max(0, stats.size() - visibleRows));
        for (int i = 0; i < visibleRows && scroll + i < stats.size(); i++) {
            PerformanceMonitor.ThreadStat s = stats.get(scroll + i);
            int ry = rowsTop + i * ROW_H;
            if ((i & 1) == 1) g.fill(px + 3, ry - 1, px + pw - 3, ry + ROW_H - 1, 0x16FFFFFF);
            int color = s.cpuPercent() > 60 ? 0xFFFF5F5F : (s.cpuPercent() > 25 ? 0xFFFFD15C : 0xFF6BDC73);
            cell(g, font, s.name(), c1, ry, c2 - 6, COL_TEXT);
            cell(g, font, String.format(Locale.ROOT, "%.1f %%", s.cpuPercent()), c2, ry, right, color);
        }
    }

    private static void drawHeader(GuiGraphicsExtractor g, Font font, int top, int[] cols, String[] names, int right) {
        for (int i = 0; i < cols.length; i++) {
            int limit = i + 1 < cols.length ? cols[i + 1] - 6 : right;
            cell(g, font, names[i], cols[i], top + 3, limit, COL_TEXT);
            if (i > 0) g.fill(cols[i] - 4, top, cols[i] - 3, top + HEAD_H, COL_SEP);
        }
        g.fill(px + 6, top + HEAD_H - 1, px + pw - 6, top + HEAD_H, COL_SEP);
    }

    private static void cell(GuiGraphicsExtractor g, Font font, String text, int x, int y, int limitX, int color) {
        if (limitX - x < 6) return;
        g.text(font, font.plainSubstrByWidth(text, limitX - x), x, y, color, false);
    }

    private static void drawFooter(GuiGraphicsExtractor g, Font font, PerformanceMonitor pm, int top) {
        g.fill(px + 6, top, px + pw - 6, top + 1, COL_SEP);
        String fps = pm.fps().isPresent() ? String.format(Locale.ROOT, "%.0f", pm.fps().getAsDouble()) : "...";
        String frame = fmtMs(pm.frameMs());
        String ctick = fmtMs(pm.clientTickMsAvg());
        String mspt = pm.serverMspt().isPresent() ? fmtMs(pm.serverMspt()) : "n/a (no local server)";
        String cpu = pm.processCpuPercent().isPresent()
                ? String.format(Locale.ROOT, "%.1f %%", pm.processCpuPercent().getAsDouble()) : "n/a";
        String line1 = "FPS " + fps + "   Frame " + frame + "   Client tick " + ctick + "   Server MSPT " + mspt;
        String line2 = "Process CPU " + cpu + "   Heap " + mib(pm.heapUsedBytes()) + " / " + mib(pm.heapMaxBytes())
                + "   Mods " + mods.size();
        int right = px + pw - 6;
        cell(g, font, line1, px + 6, top + 4, right, COL_TEXT);
        cell(g, font, line2, px + 6, top + 14, right, COL_DIM);
    }

    private static String fmtMs(OptionalDouble v) {
        return v.isPresent() ? String.format(Locale.ROOT, "%.2f ms", v.getAsDouble()) : "...";
    }

    private static String mib(long bytes) {
        return bytes <= 0 ? "n/a" : String.format(Locale.ROOT, "%.0f MiB", bytes / 1048576.0);
    }

    // ---------- input (forwarded by the console screen) ----------

    public static boolean contains(double mx, double my) {
        return visible && mx >= px && mx < px + pw && my >= py && my < py + ph;
    }

    /** @return true if the click hit the window (and was consumed) */
    public static boolean mouseClicked(double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (Keys.isLeftButton(button)) {
            if (my < py + TITLE_H && mx >= px + pw - 16) {
                setVisible(false);
                return true;
            }
            int hc = headerColumnAt(mx, my);
            if (hc >= 0) {
                if (sortCol == hc) sortDesc = !sortDesc;
                else {
                    sortCol = hc;
                    sortDesc = hc != 0;
                }
                rowCacheAt[0] = 0;
                rowCacheAt[1] = 0;
                return true;
            }
            if (mx >= px + pw - 14 && my >= py + ph - 14) {
                resizing = true;
            } else {
                int t = tabAt(mx, my);
                if (t >= 0) {
                    tab = t;
                    scroll = 0;
                } else if (my < py + TITLE_H) {
                    dragging = true;
                    dragDX = (int) mx - px;
                    dragDY = (int) my - py;
                }
            }
        }
        return true;
    }

    /** Sortable header column (0..3) under the mouse on the resource tabs, otherwise -1. */
    private static int headerColumnAt(double mx, double my) {
        if (tab == TAB_THREADS) return -1;
        int top = py + TITLE_H + TAB_H;
        if (my < top || my >= top + HEAD_H) return -1;
        int[] cols = columns();
        for (int i = 0; i < 4; i++) {
            if (mx >= cols[i] - 4 && mx < cols[i + 1] - 4) return i;
        }
        return -1;
    }

    /** @return true if a drag/resize is in progress and consumed the event */
    public static boolean mouseDragged(double mx, double my) {
        if (dragging) {
            px = Mth.clamp((int) mx - dragDX, 0, Math.max(0, screenW - pw));
            py = Mth.clamp((int) my - dragDY, 0, Math.max(0, screenH - ph));
            return true;
        }
        if (resizing) {
            pw = Mth.clamp((int) mx - px, Math.min(MIN_W, screenW - px), screenW - px);
            ph = Mth.clamp((int) my - py, Math.min(MIN_H, screenH - py), screenH - py);
            return true;
        }
        return false;
    }

    public static void mouseReleased() {
        dragging = false;
        resizing = false;
    }

    public static boolean mouseScrolled(double mx, double my, double dy) {
        if (!contains(mx, my)) return false;
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
        return true;
    }
}

package dev.streetresmon.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** FiveM-style ^0..^9 color codes. */
public final class ColorText {
    private static final int[] PALETTE = {
            0xFFFFFFFF, // ^0
            0xFFFF5F5F, // ^1 red
            0xFF6BDC73, // ^2 green
            0xFFFFD15C, // ^3 yellow
            0xFF5CB0FF, // ^4 blue
            0xFF5FE0E0, // ^5 cyan
            0xFFC084FC, // ^6 purple
            0xFFE8EAED, // ^7 white
            0xFFB04040, // ^8 dark red
            0xFF9AA0A6  // ^9 grey
    };

    private ColorText() {}

    /** Draws text with color codes, clipped at maxX. Returns the x position after the text. */
    public static int draw(GuiGraphicsExtractor g, Font font, String text, int x, int y, int defaultColor, int maxX) {
        int color = defaultColor;
        StringBuilder seg = new StringBuilder();
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '^' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                cx = flush(g, font, seg, cx, y, color, maxX);
                color = PALETTE[text.charAt(i + 1) - '0'];
                i++;
            } else {
                seg.append(c);
            }
        }
        return flush(g, font, seg, cx, y, color, maxX);
    }

    private static int flush(GuiGraphicsExtractor g, Font font, StringBuilder seg, int x, int y, int color, int maxX) {
        if (seg.length() == 0 || x >= maxX) {
            seg.setLength(0);
            return x;
        }
        String clipped = font.plainSubstrByWidth(seg.toString(), maxX - x);
        g.text(font, clipped, x, y, color, false);
        seg.setLength(0);
        return x + font.width(clipped);
    }
}

package dev.streetresmon.client;

import net.minecraft.client.input.KeyEvent;

/**
 * Minecraft 26.x uses SDL instead of GLFW: KeyEvent.key() carries SDL scancodes, keycode() SDL keycodes.
 * Both are checked so the console works regardless of which of the two a given key reports.
 */
final class Keys {
    private Keys() {}

    // SDL scancodes
    private static final int SC_ENTER = 40, SC_ESCAPE = 41, SC_F8 = 65, SC_KP_ENTER = 88;
    private static final int SC_PAGE_UP = 75, SC_PAGE_DOWN = 78, SC_DOWN = 81, SC_UP = 82;
    // SDL keycodes (scancode | 1<<30 for non-printable keys)
    private static final int KC_ENTER = 13, KC_ESCAPE = 27;
    private static final int KC_F8 = 0x40000041, KC_KP_ENTER = 0x40000058;
    private static final int KC_PAGE_UP = 0x4000004B, KC_PAGE_DOWN = 0x4000004E;
    private static final int KC_DOWN = 0x40000051, KC_UP = 0x40000052;

    static boolean isEnter(KeyEvent e) {
        return e.key() == SC_ENTER || e.key() == SC_KP_ENTER || e.keycode() == KC_ENTER || e.keycode() == KC_KP_ENTER;
    }

    static boolean isEscape(KeyEvent e) {
        return e.key() == SC_ESCAPE || e.keycode() == KC_ESCAPE;
    }

    static boolean isF8(KeyEvent e) {
        return e.key() == SC_F8 || e.keycode() == KC_F8;
    }

    static boolean isUp(KeyEvent e) { return e.key() == SC_UP || e.keycode() == KC_UP; }

    static boolean isDown(KeyEvent e) { return e.key() == SC_DOWN || e.keycode() == KC_DOWN; }

    static boolean isPageUp(KeyEvent e) { return e.key() == SC_PAGE_UP || e.keycode() == KC_PAGE_UP; }

    static boolean isPageDown(KeyEvent e) { return e.key() == SC_PAGE_DOWN || e.keycode() == KC_PAGE_DOWN; }

    /** Left mouse button: 0 in GLFW-style numbering, 1 in SDL-style numbering. Right click (GLFW 1) is harmless here. */
    static boolean isLeftButton(int button) {
        return button == 0 || button == 1;
    }
}

package dev.streetresmon.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Single place that opens/closes the StreetResmon UI. Client only. */
public final class ClientUi {
    private ClientUi() {}

    private static Screen current() {
        return Minecraft.getInstance().gui.screen();
    }

    public static void toggleConsole() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = current();
        if (s instanceof ConsoleScreen) mc.gui.setScreen(null);
        else if (s == null) mc.gui.setScreen(new ConsoleScreen());
    }

    public static void closeConsole() {
        Minecraft mc = Minecraft.getInstance();
        if (current() instanceof ConsoleScreen) mc.gui.setScreen(null);
    }

    /** @return true if the monitor was hidden before and is visible now */
    public static boolean openResmon() {
        return ResmonWindow.setVisible(true);
    }

    /** @return true if the monitor was visible and has been closed */
    public static boolean closeResmon() {
        return ResmonWindow.setVisible(false);
    }
}

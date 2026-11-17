package dev.streetresmon.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.streetresmon.StreetResmon;
import dev.streetresmon.perf.MemoryHub;
import dev.streetresmon.perf.PerformanceMonitor;
import dev.streetresmon.perf.ProfilerHub;
import dev.streetresmon.perf.SamplerHub;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Key mapping (F8 only; the resource monitor is opened exclusively via the console) and the cheap permanent measurement hooks. Client only. */
@EventBusSubscriber(modid = StreetResmon.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private static KeyMapping consoleKey;
    private static KeyMapping resmonKey;

    private ClientEvents() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        consoleKey = new KeyMapping("key.streetresmon.console", InputConstants.KEY_F8, KeyMapping.Category.MISC);
        event.register(consoleKey);
        resmonKey = new KeyMapping("key.streetresmon.resmon", InputConstants.KEY_F5, KeyMapping.Category.MISC);
        event.register(resmonKey);
    }

    /** The resource monitor is a HUD overlay so the player can keep moving while it is visible. */
    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(StreetResmon.MODID, "resmon"),
                (graphics, deltaTracker) -> ResmonWindow.drawHud(graphics));
    }

    @SubscribeEvent
    public static void clientTickPre(ClientTickEvent.Pre event) {
        SamplerHub.noteClientThread(Thread.currentThread());
        PerformanceMonitor.get().clientTickStart();
    }

    @SubscribeEvent
    public static void clientTickPost(ClientTickEvent.Post event) {
        PerformanceMonitor.get().clientTickEnd();
        MemoryHub.tick();
        ProfilerHub.onClientTick();
        while (consoleKey != null && consoleKey.consumeClick()) ClientUi.toggleConsole();
        while (resmonKey != null && resmonKey.consumeClick()) ResmonWindow.setVisible(!ResmonWindow.isVisible());
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        PerformanceMonitor.get().onFrame();
        ProfilerHub.onFrame(System.nanoTime());
    }
}

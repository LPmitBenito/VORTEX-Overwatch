package dev.streetresmon.perf;

import dev.streetresmon.StreetResmon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Measures real server tick duration (MSPT). Runs on dedicated and integrated servers. */
@EventBusSubscriber(modid = StreetResmon.MODID)
public final class ServerTickHooks {
    private ServerTickHooks() {}

    @SubscribeEvent
    public static void pre(ServerTickEvent.Pre event) {
        SamplerHub.noteServerThread(Thread.currentThread());
        PerformanceMonitor.get().serverTickStart();
    }

    @SubscribeEvent
    public static void post(ServerTickEvent.Post event) {
        PerformanceMonitor.get().serverTickEnd();
        MemoryHub.tick();
    }
}

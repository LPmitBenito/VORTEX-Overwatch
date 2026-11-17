package dev.streetresmon;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Mod entry point. All behaviour lives in event subscriber classes (see perf/ and client/). */
@Mod(StreetResmon.MODID)
public final class StreetResmon {
    public static final String MODID = "streetresmon";

    public StreetResmon(IEventBus modBus) {
        // Intentionally empty: client/server hooks register themselves via @EventBusSubscriber.
    }
}

package dev.streetresmon.command;

import dev.streetresmon.StreetResmon;
import dev.streetresmon.client.ConsoleLog;
import dev.streetresmon.client.ClientUi;
import dev.streetresmon.perf.PerformanceMonitor;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/** The commands required by the spec: resmon, help, clear, fps, mods, version. */
final class BuiltinCommands {
    private BuiltinCommands() {}

    static final class Help implements ConsoleCommand {
        public String name() { return "help"; }
        public String usage() { return "help"; }
        public String description() { return "Show all available commands"; }

        public void execute(CommandContext ctx, String[] args) {
            ctx.print("^2Available commands:");
            for (ConsoleCommand c : CommandRegistry.all()) {
                ctx.print("  ^4" + c.usage() + "^7 - " + c.description());
            }
        }
    }

    static final class Clear implements ConsoleCommand {
        public String name() { return "clear"; }
        public String usage() { return "clear"; }
        public String description() { return "Clear the console output"; }

        public void execute(CommandContext ctx, String[] args) {
            ConsoleLog.clear();
        }
    }

    static final class Resmon implements ConsoleCommand {
        public String name() { return "resmon"; }
        public String usage() { return "resmon <1|0>"; }
        public String description() { return "Show (1) or hide (0) the resource monitor overlay"; }

        public void execute(CommandContext ctx, String[] args) {
            if (args.length != 1 || !(args[0].equals("1") || args[0].equals("0"))) {
                ctx.warn("Usage: ^4resmon 1^3 | ^4resmon 0");
                return;
            }
            if (args[0].equals("1")) {
                ctx.print(ClientUi.openResmon()
                        ? "Resource monitor opened. Close the console (F8) to keep playing; use ^4resmon 0^7 to close it."
                        : "Resource monitor is already open.");
            } else {
                ctx.print(ClientUi.closeResmon() ? "Resource monitor closed." : "Resource monitor is not open.");
            }
        }
    }

    static final class Fps implements ConsoleCommand {
        public String name() { return "fps"; }
        public String usage() { return "fps"; }
        public String description() { return "Show the current FPS"; }

        public void execute(CommandContext ctx, String[] args) {
            PerformanceMonitor pm = PerformanceMonitor.get();
            OptionalDouble fps = pm.fps();
            OptionalDouble ms = pm.frameMs();
            if (fps.isEmpty()) {
                ctx.warn("FPS not measured yet (no frame window completed).");
            } else {
                ctx.print(String.format("FPS: ^2%.0f^7  (frame time %.2f ms)", fps.getAsDouble(), ms.orElse(0)));
            }
        }
    }

    static final class Mods implements ConsoleCommand {
        public String name() { return "mods"; }
        public String usage() { return "mods"; }
        public String description() { return "List all loaded mods"; }

        public void execute(CommandContext ctx, String[] args) {
            List<IModInfo> mods = ModList.get().getMods().stream()
                    .sorted(Comparator.comparing(IModInfo::getModId)).toList();
            ctx.print("^2Loaded mods: ^7" + mods.size());
            for (IModInfo info : mods) {
                ctx.print("  ^4" + info.getModId() + "^7  " + info.getVersion());
            }
        }
    }

    static final class Version implements ConsoleCommand {
        public String name() { return "version"; }
        public String usage() { return "version"; }
        public String description() { return "Show the StreetResmon version"; }

        public void execute(CommandContext ctx, String[] args) {
            String version = ModList.get().getMods().stream()
                    .filter(m -> StreetResmon.MODID.equals(m.getModId()))
                    .map(m -> m.getVersion().toString())
                    .findFirst().orElse("unknown");
            ctx.print("StreetResmon ^2" + version);
        }
    }
}

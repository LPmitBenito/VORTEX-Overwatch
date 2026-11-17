package dev.streetresmon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Parses console lines and dispatches them to registered commands. */
public final class CommandRegistry {
    private static final Map<String, ConsoleCommand> COMMANDS = new TreeMap<>();

    static {
        register(new BuiltinCommands.Help());
        register(new BuiltinCommands.Clear());
        register(new BuiltinCommands.Resmon());
        register(new BuiltinCommands.Fps());
        register(new BuiltinCommands.Mods());
        register(new BuiltinCommands.Version());
        register(new DiagCommands.Profiler());
        register(new DiagCommands.Memory());
        register(new DiagCommands.Crashlog());
    }

    private CommandRegistry() {}

    public static void register(ConsoleCommand command) {
        COMMANDS.put(command.name().toLowerCase(Locale.ROOT), command);
    }

    public static List<ConsoleCommand> all() {
        return new ArrayList<>(COMMANDS.values());
    }

    /** Splits on whitespace; the first token is the command name, the rest are arguments. */
    public static void execute(String line, CommandContext ctx) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        String[] tokens = trimmed.split("\\s+");
        String name = tokens[0].toLowerCase(Locale.ROOT);
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        ConsoleCommand command = COMMANDS.get(name);
        if (command == null) {
            ctx.error("Unknown command '" + tokens[0] + "'. Type ^2help^1 for a list of commands.");
            return;
        }
        try {
            command.execute(ctx, args);
        } catch (RuntimeException e) {
            ctx.error("Command '" + name + "' failed: " + e);
        }
    }
}

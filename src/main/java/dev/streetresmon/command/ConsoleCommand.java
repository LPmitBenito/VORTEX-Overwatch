package dev.streetresmon.command;

/** One console command. Implement this and register it in {@link CommandRegistry} to extend the console. */
public interface ConsoleCommand {
    String name();

    String usage();

    String description();

    void execute(CommandContext ctx, String[] args);
}

package dev.streetresmon.command;

/** Output channel handed to commands. Color codes ^1..^9 (FiveM style) are allowed in text. */
public interface CommandContext {
    void print(String text);

    void warn(String text);

    void error(String text);
}

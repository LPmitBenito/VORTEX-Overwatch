package dev.streetresmon.core.module;

/** A feature of StreetResmon that can be started and stopped independently. */
public interface StreetModule {
    String id();

    String displayName();

    Side side();

    default void start() {}

    default void stop() {}
}

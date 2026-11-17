package dev.streetresmon.core;

/** How trustworthy a displayed value is. The UI must show this next to every number. */
public enum Quality {
    /** Read directly from the JVM / game / a counter that really counts the thing. */
    MEASURED,
    /** Derived by a documented approximation (e.g. stack sampling). Shown with a "~" prefix. */
    ESTIMATED,
    /** No reliable source exists (or the source is not connected). Shown as "n/a". */
    UNAVAILABLE
}

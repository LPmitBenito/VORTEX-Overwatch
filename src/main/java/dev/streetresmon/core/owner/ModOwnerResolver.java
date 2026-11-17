package dev.streetresmon.core.owner;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a class name to the "owner" of that code via registered package prefixes (longest match wins).
 * Mod packages must be registered by the caller; unknown packages resolve to null (never guessed).
 */
public final class ModOwnerResolver {
    public static final String JVM = "jvm";
    public static final String LIBRARY = "library";
    public static final String VANILLA = "minecraft";
    public static final String LOADER = "loader";

    private static final int MAX_NEGATIVE_CACHE = 20_000;

    private final Map<String, String> prefixToOwner = new ConcurrentHashMap<>();
    /** normalized mod id -> real mod id, used by the package-name heuristic */
    private final Map<String, String> modIds = new ConcurrentHashMap<>();
    private final Set<String> unresolved = ConcurrentHashMap.newKeySet();

    public ModOwnerResolver() {
        for (String p : new String[] {"java", "javax", "jdk", "sun", "com.sun"}) register(JVM, p);
        for (String p : new String[] {"io.netty", "org.lwjgl", "com.google", "org.apache", "it.unimi", "org.slf4j", "com.mojang.blaze3d"})
            register(LIBRARY, p);
        register(VANILLA, "net.minecraft");
        register(VANILLA, "com.mojang");
        register(LOADER, "net.neoforged");
        register(LOADER, "cpw.mods");
        register(LOADER, "org.spongepowered.asm");
    }

    /** Longer prefixes override shorter ones (e.g. com.mojang.blaze3d -> library, com.mojang -> minecraft). */
    public void register(String ownerId, String packagePrefix) {
        prefixToOwner.put(packagePrefix, ownerId);
    }

    /**
     * Enables a HEURISTIC for unregistered classes: if a package segment equals a mod id (ignoring case, '_' and '-'),
     * e.g. com.example.examplemod.Foo for mod "examplemod", the package up to that segment is mapped to that mod.
     * Mods whose packages do not contain their id stay unresolved (never guessed further). Ids shorter than 3
     * characters and the ids of minecraft/neoforge are ignored to avoid false matches.
     */
    public void registerModIds(Collection<String> ids) {
        for (String id : ids) {
            String n = normalize(id);
            if (n.length() < 3 || n.equals("minecraft") || n.equals("neoforge") || n.equals("forge")) continue;
            modIds.put(n, id);
        }
        unresolved.clear();
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /** @return owner id or null if neither a registered prefix nor the id heuristic matches */
    public String resolve(String className) {
        String c = className;
        while (true) {
            String o = prefixToOwner.get(c);
            if (o != null) return o;
            int dot = c.lastIndexOf('.');
            if (dot < 0) break;
            c = c.substring(0, dot);
        }
        return heuristic(className);
    }

    private String heuristic(String className) {
        if (modIds.isEmpty() || unresolved.contains(className)) return null;
        String[] seg = className.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < seg.length - 1; i++) { // the last segment is the class name itself
            if (i > 0) prefix.append('.');
            prefix.append(seg[i]);
            String real = modIds.get(normalize(seg[i]));
            if (real != null) {
                prefixToOwner.putIfAbsent(prefix.toString(), real);
                return real;
            }
        }
        if (unresolved.size() < MAX_NEGATIVE_CACHE) unresolved.add(className);
        return null;
    }

    /** true for owners that represent an actual mod (not JVM, vanilla, loader or a bundled library). */
    public static boolean isMod(String owner) {
        return owner != null && !owner.equals(JVM) && !owner.equals(VANILLA)
                && !owner.equals(LOADER) && !owner.equals(LIBRARY);
    }
}

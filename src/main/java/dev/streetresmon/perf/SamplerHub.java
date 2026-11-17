package dev.streetresmon.perf;

import dev.streetresmon.core.owner.ModOwnerResolver;
import dev.streetresmon.core.resmon.ModSampleTable;
import dev.streetresmon.core.resmon.StackSampler;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Owns the stack samplers for the client thread and the server thread. Samplers only run while at least one user
 * (resmon window, profiler) has acquired them, so the idle cost is zero. Works on client and dedicated server.
 */
public final class SamplerHub {
    public static final long INTERVAL_MS = 25;

    private static final ModOwnerResolver RESOLVER = new ModOwnerResolver();
    private static final Set<String> USERS = new HashSet<>();
    private static volatile Thread clientThread;
    private static volatile Thread serverThread;
    private static volatile ModSampleTable clientTable;
    private static volatile ModSampleTable serverTable;
    private static StackSampler clientSampler;
    private static StackSampler serverSampler;
    private static boolean idsRegistered;

    private SamplerHub() {}

    /** Resolver with the id heuristic enabled for all loaded mods. */
    public static synchronized ModOwnerResolver resolver() {
        if (!idsRegistered) {
            ModList list = ModList.get();
            if (list != null) {
                RESOLVER.registerModIds(list.getMods().stream().map(IModInfo::getModId).toList());
                idsRegistered = true;
            }
        }
        return RESOLVER;
    }

    public static void noteClientThread(Thread t) {
        if (clientThread == t) return;
        synchronized (SamplerHub.class) {
            clientThread = t;
            if (!USERS.isEmpty()) startClient();
        }
    }

    public static void noteServerThread(Thread t) {
        if (serverThread == t) return;
        synchronized (SamplerHub.class) {
            serverThread = t;
            if (!USERS.isEmpty()) startServer();
        }
    }

    public static synchronized void acquire(String user) {
        boolean first = USERS.isEmpty();
        USERS.add(user);
        if (first) {
            startClient();
            startServer();
        }
    }

    public static synchronized void release(String user) {
        USERS.remove(user);
        if (USERS.isEmpty()) {
            stop(clientSampler);
            stop(serverSampler);
            clientSampler = null;
            serverSampler = null;
            clientTable = null;
            serverTable = null;
        }
    }

    private static void stop(StackSampler s) {
        if (s != null) s.close();
    }

    private static void startClient() {
        stop(clientSampler);
        clientSampler = null;
        clientTable = null;
        Thread t = clientThread;
        if (t == null) return;
        ModSampleTable table = new ModSampleTable(resolver(), INTERVAL_MS, System.nanoTime());
        clientTable = table;
        clientSampler = new StackSampler(t, table, INTERVAL_MS);
        clientSampler.start();
    }

    private static void startServer() {
        stop(serverSampler);
        serverSampler = null;
        serverTable = null;
        Thread t = serverThread;
        if (t == null) return;
        ModSampleTable table = new ModSampleTable(resolver(), INTERVAL_MS, System.nanoTime());
        serverTable = table;
        serverSampler = new StackSampler(t, table, INTERVAL_MS);
        serverSampler.start();
    }

    /** null while sampling is off or the thread is not known yet */
    public static ModSampleTable clientTable() { return clientTable; }

    /** null while sampling is off or no server thread exists in this JVM (e.g. connected to a remote server) */
    public static ModSampleTable serverTable() { return serverTable; }
}

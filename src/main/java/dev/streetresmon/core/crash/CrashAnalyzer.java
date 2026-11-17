package dev.streetresmon.core.crash;

import dev.streetresmon.core.owner.ModOwnerResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic analysis of crash reports / stack traces. Everything it names as "possible cause" is a guess
 * derived from package names (and, where present, loader annotations) and is always labelled unconfirmed.
 */
public final class CrashAnalyzer {
    public enum Category {
        NULL_POINTER, OUT_OF_MEMORY, STACK_OVERFLOW, LINKAGE, MIXIN, CONCURRENCY, ILLEGAL_ARGUMENT_OR_STATE, CAST_OR_INDEX, OTHER
    }

    public enum Confidence {
        NONE("None"), UNCONFIRMED_WEAK("Unconfirmed (weak)"), UNCONFIRMED("Unconfirmed");
        public final String label;
        Confidence(String l) { label = l; }
    }

    public record Frame(String className, String method, String file, int line, String moduleHint) {
        public String location() { return line >= 0 ? file + ":" + line : file; }
    }

    public record Trace(String type, String message, List<Frame> frames) {}

    public record ErrorGroup(String signature, String type, int count, String location, String suspect) {}

    public record Report(boolean detected, boolean fullCrashReport, String description, String exceptionType, String message,
                         Category category, String suspect, Confidence confidence, String location,
                         List<String> topFrames, Map<String, Integer> modFrameCounts) {

        public String toText() {
            StringBuilder sb = new StringBuilder("STREET RESMON CRASH ANALYZER\n\n");
            if (!detected) return sb.append("No crash or stack trace detected.\n").toString();
            sb.append(fullCrashReport ? "Crash detected!\n\n" : "Stack trace detected (no full crash report header).\n\n");
            sb.append("Possible cause:\n").append(suspect == null ? "No mod identified (vanilla/loader/JVM code only, or packages not registered)" : suspect).append("\n\n");
            sb.append("Error:\n").append(exceptionType).append(message == null || message.isEmpty() ? "" : ": " + message).append("\n\n");
            sb.append("Category:\n").append(category).append("\n\n");
            sb.append("Location:\n").append(location == null ? "unknown" : location).append("\n\n");
            sb.append("Confidence:\n").append(confidence.label).append("\n");
            if (!modFrameCounts.isEmpty()) sb.append("\nMod frames in stack: ").append(modFrameCounts).append("\n");
            return sb.toString();
        }
    }

    private static final Pattern AT = Pattern.compile("^\\s*at\\s+(\\S+?)\\(([^()]*)\\)(.*)$");
    private static final Pattern HEAD = Pattern.compile("^(?:Caused by:\\s*|Exception in thread \"[^\"]*\"\\s*)?((?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable)[\\w$]*)(?::\\s?(.*))?$");
    private static final Pattern FILE_LINE = Pattern.compile("^(.*?):(\\d+)$");
    private static final Pattern JAR_HINT = Pattern.compile("~?\\[([^\\]%:!]+?)\\.jar");

    private final ModOwnerResolver resolver;

    public CrashAnalyzer(ModOwnerResolver resolver) { this.resolver = resolver; }

    /** Parses all exception chains in the text. Each element is one chain (outermost first, root cause last). */
    List<List<Trace>> parse(String text) {
        List<List<Trace>> chains = new ArrayList<>();
        List<Trace> chain = null;
        String type = null, msg = null;
        List<Frame> frames = null;
        for (String raw : text.split("\\R")) {
            String line = raw;
            Matcher at = AT.matcher(line);
            if (at.matches()) {
                if (frames != null) frames.add(frame(at.group(1), at.group(2), at.group(3)));
                continue;
            }
            Matcher h = HEAD.matcher(line.trim());
            if (h.matches() && !line.startsWith(" ") && !line.startsWith("\t")) {
                boolean caused = line.startsWith("Caused by:");
                if (type != null) chain.add(new Trace(type, msg, frames));
                if (!caused || chain == null) {
                    chain = new ArrayList<>();
                    chains.add(chain);
                }
                type = h.group(1);
                msg = h.group(2) == null ? "" : h.group(2);
                frames = new ArrayList<>();
            }
        }
        if (type != null) chain.add(new Trace(type, msg, frames));
        // a chain whose headers never got frames is still reported, but pure noise (no frames anywhere) is dropped
        chains.removeIf(c -> c.stream().allMatch(t -> t.frames().isEmpty()));
        return chains;
    }

    private Frame frame(String qualified, String src, String tail) {
        String moduleHint = null;
        String q = qualified;
        int slash = q.lastIndexOf('/');
        if (slash >= 0) {
            String modulePart = q.substring(0, slash);
            q = q.substring(slash + 1);
            int at = modulePart.lastIndexOf('@');
            if (at > 0) {
                String id = modulePart.substring(0, at);
                int s2 = id.lastIndexOf('/');
                moduleHint = s2 >= 0 ? id.substring(s2 + 1) : id;
            }
        }
        int dot = q.lastIndexOf('.');
        String cls = dot < 0 ? q : q.substring(0, dot);
        String method = dot < 0 ? "" : q.substring(dot + 1);
        String file = src;
        int ln = -1;
        Matcher fl = FILE_LINE.matcher(src);
        if (fl.matches()) {
            file = fl.group(1);
            ln = Integer.parseInt(fl.group(2));
        }
        if (moduleHint == null) {
            Matcher j = JAR_HINT.matcher(tail == null ? "" : tail);
            if (j.find()) moduleHint = "jar:" + j.group(1);
        }
        return new Frame(cls, method, file, ln, moduleHint);
    }

    private String ownerOf(Frame f) {
        String o = resolver.resolve(f.className());
        if (ModOwnerResolver.isMod(o)) return o;
        return null; // jar/module hints are shown as evidence elsewhere but never trusted as an owner
    }

    private static Category categorize(Trace root, List<Trace> chain) {
        String t = root.type();
        String simple = t.substring(t.lastIndexOf('.') + 1);
        String msg = root.message() == null ? "" : root.message();
        if (simple.contains("Mixin") || msg.contains("Mixin apply failed") || msg.contains("InvalidInjection")) return Category.MIXIN;
        switch (simple) {
            case "NullPointerException": return Category.NULL_POINTER;
            case "OutOfMemoryError": return Category.OUT_OF_MEMORY;
            case "StackOverflowError": return Category.STACK_OVERFLOW;
            case "ConcurrentModificationException": return Category.CONCURRENCY;
            case "ClassNotFoundException": case "NoClassDefFoundError": case "NoSuchMethodError":
            case "NoSuchFieldError": case "AbstractMethodError": case "IncompatibleClassChangeError":
            case "LinkageError": case "UnsatisfiedLinkError": return Category.LINKAGE;
            case "IllegalStateException": case "IllegalArgumentException": return Category.ILLEGAL_ARGUMENT_OR_STATE;
            case "ClassCastException": case "IndexOutOfBoundsException": case "ArrayIndexOutOfBoundsException": return Category.CAST_OR_INDEX;
            default: return Category.OTHER;
        }
    }

    public Report analyze(String text) {
        List<List<Trace>> chains = parse(text);
        boolean full = text.contains("---- Minecraft Crash Report ----");
        if (chains.isEmpty()) {
            return new Report(full, full, "", "", "", Category.OTHER, null, Confidence.NONE, null, List.of(), Map.of());
        }
        List<Trace> chain = chains.get(0);
        Trace root = chain.get(chain.size() - 1);
        String desc = "";
        Matcher d = Pattern.compile("(?m)^Description:\\s*(.*)$").matcher(text);
        if (d.find()) desc = d.group(1).trim();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Trace tr : chain) for (Frame f : tr.frames()) {
            String o = ownerOf(f);
            if (o != null) counts.merge(o, 1, Integer::sum);
        }
        String suspect = null;
        Confidence conf = Confidence.NONE;
        String location = root.frames().isEmpty() ? null : root.frames().get(0).location();
        // first: nearest mod frame in the root cause's stack
        for (int i = 0; i < root.frames().size(); i++) {
            String o = ownerOf(root.frames().get(i));
            if (o != null) {
                suspect = o;
                conf = i < 3 ? Confidence.UNCONFIRMED : Confidence.UNCONFIRMED_WEAK;
                location = root.frames().get(i).location();
                break;
            }
        }
        // otherwise: any mod frame in an outer trace of the chain (weak)
        if (suspect == null) {
            outer:
            for (Trace tr : chain) for (Frame f : tr.frames()) {
                String o = ownerOf(f);
                if (o != null) { suspect = o; conf = Confidence.UNCONFIRMED_WEAK; location = f.location(); break outer; }
            }
        }
        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(8, root.frames().size()); i++) {
            Frame f = root.frames().get(i);
            top.add(f.className() + "." + f.method() + "(" + f.location() + ")");
        }
        return new Report(true, full, desc, root.type(), root.message(), categorize(root, chain), suspect, conf, location, top, counts);
    }

    /** Groups all exception chains found in a text (e.g. a whole log) by root cause type + top frame. */
    public List<ErrorGroup> groupErrors(String text) {
        Map<String, int[]> count = new LinkedHashMap<>();
        Map<String, String[]> info = new LinkedHashMap<>();
        for (List<Trace> chain : parse(text)) {
            Trace root = chain.get(chain.size() - 1);
            Frame top = root.frames().isEmpty() ? null : root.frames().get(0);
            String sig = root.type() + (top == null ? "" : " @ " + top.className() + "." + top.method());
            count.computeIfAbsent(sig, k -> new int[1])[0]++;
            if (!info.containsKey(sig)) {
                String suspect = null;
                for (Frame f : root.frames()) { String o = ownerOf(f); if (o != null) { suspect = o; break; } }
                info.put(sig, new String[] {root.type(), top == null ? null : top.location(), suspect});
            }
        }
        List<ErrorGroup> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : count.entrySet()) {
            String[] i = info.get(e.getKey());
            out.add(new ErrorGroup(e.getKey(), i[0], e.getValue()[0], i[1], i[2]));
        }
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }
}

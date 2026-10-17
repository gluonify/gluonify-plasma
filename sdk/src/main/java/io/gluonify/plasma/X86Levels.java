package io.gluonify.plasma;

/**
 * x86-64 instruction-set levels (microarchitectures v1 to v4 of the System V ABI): an executable built for one level does not start on a processor
 * of a lower level (illegal instruction). Nodes advertise theirs ({@link io.gluonify.plasma.ws.Msg.Host#x86Level}), deliverables theirs
 * ({@link AppSpec#artifactLevel}).
 */
public final class X86Levels {
    private X86Levels() {}

    /** "v1" to "v4" (case-insensitive, {@code x86-64-} prefix accepted) -> 1 to 4; null, empty or unknown: 1. */
    public static int parse(String v) {
        if (v == null) return 1;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("x86-64-")) s = s.substring(7);
        return switch (s) {
            case "v2" -> 2;
            case "v3" -> 3;
            case "v4" -> 4;
            default -> 1;
        };
    }

    /** True if a node of level {@code nodeLevel} (null: unknown, old agent) can run a deliverable that requires {@code required}. Unknown level: accepted (compatibility). */
    public static boolean runs(String nodeLevel, int required) {
        return required <= 1 || nodeLevel == null || parse(nodeLevel) >= required;
    }
}

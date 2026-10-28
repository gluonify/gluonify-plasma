package io.gluonify.plasma;

/**
 * x86-64 instruction-set levels (microarchitectures v1 to v4 of the System V ABI): an executable built for one level does not start on a processor
 * of a lower level (illegal instruction). Nodes advertise theirs ({@link io.gluonify.plasma.ws.Msg.Host#x86Level}), deliverables theirs
 * ({@link AppSpec#artifactLevel}).
 */
public final class X86Levels {
    /** The levels in order: the position of a level plus one is its number. */
    private static final java.util.List<String> LEVELS = java.util.List.of("v1", "v2", "v3", "v4");

    private X86Levels() {}

    /** "v1" to "v4" (case-insensitive, {@code x86-64-} prefix accepted) -> 1 to 4; null, empty or unknown: 1. */
    public static int parse(final String v) {
        if (v == null) return 1;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("x86-64-")) s = s.substring(7);
        return Math.max(1, LEVELS.indexOf(s) + 1);
    }

    /** True if a node of level {@code nodeLevel} (null: unknown, old agent) can run a deliverable that requires {@code required}. Unknown level: accepted (compatibility). */
    public static boolean runs(final String nodeLevel, final int required) {
        return required <= 1 || nodeLevel == null || parse(nodeLevel) >= required;
    }
}

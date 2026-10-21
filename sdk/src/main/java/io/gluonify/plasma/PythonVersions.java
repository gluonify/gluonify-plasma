package io.gluonify.plasma;

/**
 * Python minor versions (gluonify-codex/python.md § 3 bis.2): a Python application (archive of Bup's {@code python} framework) only runs with the
 * {@code python3} of the same minor version (venv and {@code cp313} wheels tied to that version). Nodes advertise theirs
 * ({@link io.gluonify.plasma.ws.Msg.Host#pythonVersion}), applications theirs ({@link AppSpec#pythonVersion}).
 */
public final class PythonVersions {
    private PythonVersions() {}

    /** Minor version "3.X": nothing else (no patch, no suffix). */
    public static final java.util.regex.Pattern MINOR = java.util.regex.Pattern.compile("3\\.\\d{1,2}");

    /** Canonical form ("3.13"); null for null or empty; "3.13.5" is reduced to "3.13"; any other form is rejected. */
    public static String normalize(final String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        var m = java.util.regex.Pattern.compile("(3\\.\\d{1,2})(?:\\.\\d+)?").matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("pythonVersion: minor version \"3.X\" expected (for example \"3.13\")");
        return m.group(1);
    }

    /**
     * Does an application of version {@code app} run on a node that advertises {@code node}? Non-Python application ({@code app} null): yes, everywhere. Python
     * application: only at an equal minor version; a node without python3 (or from an old agent, {@code node} null) receives none.
     */
    public static boolean runs(final String node, final String app) {
        return app == null || app.equals(node);
    }
}

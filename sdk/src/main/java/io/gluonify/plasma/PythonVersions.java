package io.gluonify.plasma;

/**
 * Versions mineures de Python (gluonify-codex/python.md § 3 bis.2) : une application Python (archive du framework {@code python} de Bup) ne tourne qu'avec le
 * {@code python3} de la même version mineure (venv et roues {@code cp313} liés à cette version). Les nœuds annoncent la leur
 * ({@link io.gluonify.plasma.ws.Msg.Host#pythonVersion}), les applications la leur ({@link AppSpec#pythonVersion}).
 */
public final class PythonVersions {
    private PythonVersions() {}

    /** Version mineure « 3.X » : rien d'autre (ni correctif, ni suffixe). */
    public static final java.util.regex.Pattern MINOR = java.util.regex.Pattern.compile("3\\.\\d{1,2}");

    /** Forme canonique (« 3.13 ») ; null pour null ou vide ; « 3.13.5 » est ramené à « 3.13 » ; toute autre forme est refusée. */
    public static String normalize(String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        var m = java.util.regex.Pattern.compile("(3\\.\\d{1,2})(?:\\.\\d+)?").matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("pythonVersion : version mineure « 3.X » attendue (par exemple « 3.13 »)");
        return m.group(1);
    }

    /**
     * Une application de version {@code app} tourne-t-elle sur un nœud qui annonce {@code node} ? Application non Python ({@code app} null) : oui, partout. Application
     * Python : seulement à version mineure égale ; un nœud sans python3 (ou d'un ancien agent, {@code node} null) n'en reçoit aucune.
     */
    public static boolean runs(String node, String app) {
        return app == null || app.equals(node);
    }
}

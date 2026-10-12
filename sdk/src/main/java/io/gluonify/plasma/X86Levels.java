package io.gluonify.plasma;

/**
 * Niveaux du jeu d'instructions x86-64 (micro-architectures v1 à v4 de l'ABI System V) : un exécutable construit pour un niveau ne démarre pas sur un processeur
 * de niveau inférieur (instruction illégale). Les nœuds annoncent le leur ({@link io.gluonify.plasma.ws.Msg.Host#x86Level}), les livrables le leur
 * ({@link AppSpec#artifactLevel}).
 */
public final class X86Levels {
    private X86Levels() {}

    /** « v1 » à « v4 » (casse indifférente, préfixe {@code x86-64-} accepté) -> 1 à 4 ; null, vide ou inconnu : 1. */
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

    /** Vrai si un nœud de niveau {@code nodeLevel} (null : inconnu, ancien agent) peut exécuter un livrable qui exige {@code required}. Niveau inconnu : accepté (compatibilité). */
    public static boolean runs(String nodeLevel, int required) {
        return required <= 1 || nodeLevel == null || parse(nodeLevel) >= required;
    }
}

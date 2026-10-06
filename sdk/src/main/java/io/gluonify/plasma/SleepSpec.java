package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Mode endormi (scale-to-zero) d'une application : sans requête depuis {@code freezeAfterSeconds}, ses répliques sont gelées (cgroup freezer : mémoire conservée, réveil en quelques
 * millisecondes) ; sans requête depuis {@code stopAfterSeconds}, elles sont arrêtées (mémoire rendue, réveil en ~0,2 s pour une application native légère). La requête suivante est
 * retenue par l'ingress pendant au plus {@code wakeTimeoutMs} le temps du réveil. Désactivé par défaut : rien ne change pour une application qui ne le demande pas.
 */
@Schema(description = "Mode endormi : gel puis arrêt d'une application inactive, réveil par la requête suivante.")
public record SleepSpec(@Schema(description = "true : activer le mode endormi (défaut false)", example = "true") Boolean enabled,
                        @Schema(description = "Inactivité (secondes) avant le gel (1 à 86400, défaut 60)", example = "60") Integer freezeAfterSeconds,
                        @Schema(description = "Inactivité (secondes) avant l'arrêt, comptée depuis la dernière requête, donc supérieure à freezeAfterSeconds (défaut : le plus grand de 900 et freezeAfterSeconds + 60)", example = "900") Integer stopAfterSeconds,
                        @Schema(description = "Attente maximale (ms) d'une requête pendant le réveil, avant la réponse 503 + Retry-After (100 à 60000, défaut 5000)", example = "5000") Integer wakeTimeoutMs) {
    public static final int DEFAULT_FREEZE_SECONDS = 60;
    public static final int DEFAULT_STOP_SECONDS = 900;
    public static final int DEFAULT_WAKE_TIMEOUT_MS = 5000;
    public static final int MAX_SECONDS = 30 * 86400;

    public SleepSpec {
        enabled = enabled != null && enabled;
        freezeAfterSeconds = freezeAfterSeconds == null ? DEFAULT_FREEZE_SECONDS : freezeAfterSeconds;
        if (freezeAfterSeconds < 1 || freezeAfterSeconds > 86400) throw new IllegalArgumentException("sleep.freezeAfterSeconds : 1 à 86400");
        stopAfterSeconds = stopAfterSeconds == null ? Math.max(DEFAULT_STOP_SECONDS, freezeAfterSeconds + 60) : stopAfterSeconds;
        if (stopAfterSeconds > MAX_SECONDS) throw new IllegalArgumentException("sleep.stopAfterSeconds : " + MAX_SECONDS + " au plus");
        if (stopAfterSeconds <= freezeAfterSeconds) throw new IllegalArgumentException("sleep.stopAfterSeconds doit dépasser freezeAfterSeconds");
        wakeTimeoutMs = wakeTimeoutMs == null ? DEFAULT_WAKE_TIMEOUT_MS : wakeTimeoutMs;
        if (wakeTimeoutMs < 100 || wakeTimeoutMs > 60000) throw new IllegalArgumentException("sleep.wakeTimeoutMs : 100 à 60000");
    }

    /** Mode désactivé (valeurs par défaut des délais). */
    public static SleepSpec off() {
        return new SleepSpec(false, null, null, null);
    }
}

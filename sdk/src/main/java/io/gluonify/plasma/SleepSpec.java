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
                        @Schema(description = "Attente maximale (ms) d'une requête pendant le réveil, avant la réponse 503 + Retry-After (100 à 60000, défaut 5000)", example = "5000") Integer wakeTimeoutMs,
                        @Schema(description = "Politique de réveil des répliques : combien d'abord, quand en réveiller d'autres (absent : les valeurs du cluster, gluonify.higgs.sleep.wake.*)") Wake wake) {
    public static final int DEFAULT_FREEZE_SECONDS = 60;
    public static final int DEFAULT_STOP_SECONDS = 900;
    public static final int DEFAULT_WAKE_TIMEOUT_MS = 5000;
    public static final int DEFAULT_INITIAL_REPLICAS = 1;
    public static final int DEFAULT_INFLIGHT_PER_REPLICA = 20;
    public static final int DEFAULT_HELD_REQUESTS = 1;
    public static final int DEFAULT_SLOW_WAKE_MS = 500;
    public static final int DEFAULT_STEP_REPLICAS = 1;
    public static final int DEFAULT_COOLDOWN_SECONDS = 10;
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

    /** Compatibilité : sans politique de réveil propre (valeurs du cluster). */
    public SleepSpec(Boolean enabled, Integer freezeAfterSeconds, Integer stopAfterSeconds, Integer wakeTimeoutMs) {
        this(enabled, freezeAfterSeconds, stopAfterSeconds, wakeTimeoutMs, null);
    }

    /**
     * Quand réveiller une deuxième (troisième…) réplique. {@code initialReplicas} répliques se réveillent d'abord (défaut 1, les gelées en premier) ; chaque ingress en réveille ensuite
     * {@code stepReplicas} de plus (sur d'autres nœuds d'abord), au plus une fois par {@code cooldownSeconds}, quand un seuil est franchi : requêtes en cours par réplique éveillée,
     * requêtes retenues depuis plus de {@code slowWakeMs}, débit par réplique. Un champ absent prend la valeur du cluster (gluonify.higgs.sleep.wake.*), puis le défaut ci-dessous.
     */
    @Schema(description = "Politique de réveil des répliques d'une application endormie.")
    public record Wake(@Schema(description = "Répliques réveillées par la première requête (défaut 1 ; au moins le nombre de répliques : toutes ensemble)", example = "1") Integer initialReplicas,
                       @Schema(description = "Seuils qui réveillent des répliques de plus") AdditionalWhen additionalWhen) {
        public Wake {
            if (initialReplicas != null && (initialReplicas < 1 || initialReplicas > 100)) throw new IllegalArgumentException("sleep.wake.initialReplicas : 1 à 100");
        }
    }

    /** Seuils (par ingress) au-delà desquels une réplique de plus est réveillée ; un champ absent prend la valeur du cluster, puis le défaut. */
    @Schema(description = "Seuils de réveil de répliques supplémentaires (mesurés par chaque ingress).")
    public record AdditionalWhen(@Schema(description = "Requêtes en cours par réplique éveillée au-delà desquelles une réplique de plus est réveillée (défaut 20 ; 0 : critère désactivé)", example = "20") Integer inflightPerReplica,
                                 @Schema(description = "Requêtes retenues (en attente d'un réveil) depuis plus de slowWakeMs qui en réveillent une de plus (défaut 1 ; 0 : désactivé)", example = "1") Integer heldRequests,
                                 @Schema(description = "Attente (ms) d'une requête retenue au-delà de laquelle elle compte pour heldRequests (défaut 500)", example = "500") Integer slowWakeMs,
                                 @Schema(description = "Requêtes par seconde et par réplique éveillée au-delà desquelles une réplique de plus est réveillée (défaut 0 : désactivé)", example = "0") Integer ratePerReplica,
                                 @Schema(description = "Répliques réveillées à chaque décision (défaut 1)", example = "1") Integer stepReplicas,
                                 @Schema(description = "Délai minimal (secondes) entre deux décisions de réveil supplémentaire (défaut 10)", example = "10") Integer cooldownSeconds) {
        public AdditionalWhen {
            nonNeg("inflightPerReplica", inflightPerReplica, 100000);
            nonNeg("heldRequests", heldRequests, 100000);
            nonNeg("slowWakeMs", slowWakeMs, 60000);
            nonNeg("ratePerReplica", ratePerReplica, 1000000);
            if (stepReplicas != null && (stepReplicas < 1 || stepReplicas > 100)) throw new IllegalArgumentException("sleep.wake.additionalWhen.stepReplicas : 1 à 100");
            nonNeg("cooldownSeconds", cooldownSeconds, 86400);
        }

        private static void nonNeg(String name, Integer v, int max) {
            if (v != null && (v < 0 || v > max)) throw new IllegalArgumentException("sleep.wake.additionalWhen." + name + " : 0 à " + max);
        }
    }

    /** Mode désactivé (valeurs par défaut des délais). */
    public static SleepSpec off() {
        return new SleepSpec(false, null, null, null, null);
    }
}

package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Sleep mode (scale-to-zero) of an application: with no request for {@code freezeAfterSeconds}, its replicas are frozen (cgroup freezer: memory kept, wake-up in a few
 * milliseconds); with no request for {@code stopAfterSeconds}, they are stopped (memory released, wake-up in ~0.2 s for a light native application). The next request is
 * held by the ingress for at most {@code wakeTimeoutMs} while waking up. Disabled by default: nothing changes for an application that does not ask for it.
 */
@Schema(description = "Sleep mode: freeze then stop of an idle application, woken by the next request.")
public record SleepSpec(@Schema(description = "true: enable sleep mode (default false)", example = "true") Boolean enabled,
                        @Schema(description = "Inactivity (seconds) before the freeze (1 to 86400, default 60)", example = "60") Integer freezeAfterSeconds,
                        @Schema(description = "Inactivity (seconds) before the stop, counted from the last request, hence greater than freezeAfterSeconds (default: the larger of 900 and freezeAfterSeconds + 60)", example = "900") Integer stopAfterSeconds,
                        @Schema(description = "Maximum wait (ms) of a request during wake-up, before the 503 + Retry-After response (100 to 60000, default 5000)", example = "5000") Integer wakeTimeoutMs,
                        @Schema(description = "Replica wake-up policy: how many first, when to wake others (absent: the cluster values, gluonify.higgs.sleep.wake.*)") Wake wake) {
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
        if (freezeAfterSeconds < 1 || freezeAfterSeconds > 86400) throw new IllegalArgumentException("sleep.freezeAfterSeconds: 1 to 86400");
        stopAfterSeconds = stopAfterSeconds == null ? Math.max(DEFAULT_STOP_SECONDS, freezeAfterSeconds + 60) : stopAfterSeconds;
        if (stopAfterSeconds > MAX_SECONDS) throw new IllegalArgumentException("sleep.stopAfterSeconds: " + MAX_SECONDS + " at most");
        if (stopAfterSeconds <= freezeAfterSeconds) throw new IllegalArgumentException("sleep.stopAfterSeconds must exceed freezeAfterSeconds");
        wakeTimeoutMs = wakeTimeoutMs == null ? DEFAULT_WAKE_TIMEOUT_MS : wakeTimeoutMs;
        if (wakeTimeoutMs < 100 || wakeTimeoutMs > 60000) throw new IllegalArgumentException("sleep.wakeTimeoutMs: 100 to 60000");
    }

    /** Compatibility: no own wake-up policy (cluster values). */
    public SleepSpec(Boolean enabled, Integer freezeAfterSeconds, Integer stopAfterSeconds, Integer wakeTimeoutMs) {
        this(enabled, freezeAfterSeconds, stopAfterSeconds, wakeTimeoutMs, null);
    }

    /**
     * When to wake a second (third…) replica. {@code initialReplicas} replicas wake first (default 1, the frozen ones first); each ingress then wakes
     * {@code stepReplicas} more (on other nodes first), at most once per {@code cooldownSeconds}, when a threshold is crossed: in-flight requests per awake replica,
     * requests held for more than {@code slowWakeMs}, rate per replica. An absent field takes the cluster value (gluonify.higgs.sleep.wake.*), then the default below.
     */
    @Schema(description = "Replica wake-up policy of a sleeping application.")
    public record Wake(@Schema(description = "Replicas woken by the first request (default 1; at least the number of replicas: all together)", example = "1") Integer initialReplicas,
                       @Schema(description = "Thresholds that wake more replicas") AdditionalWhen additionalWhen) {
        public Wake {
            if (initialReplicas != null && (initialReplicas < 1 || initialReplicas > 100)) throw new IllegalArgumentException("sleep.wake.initialReplicas: 1 to 100");
        }
    }

    /** Thresholds (per ingress) beyond which one more replica is woken; an absent field takes the cluster value, then the default. */
    @Schema(description = "Wake-up thresholds for additional replicas (measured by each ingress).")
    public record AdditionalWhen(@Schema(description = "In-flight requests per awake replica beyond which one more replica is woken (default 20; 0: criterion disabled)", example = "20") Integer inflightPerReplica,
                                 @Schema(description = "Held requests (waiting for a wake-up) for more than slowWakeMs that wake one more replica (default 1; 0: disabled)", example = "1") Integer heldRequests,
                                 @Schema(description = "Wait (ms) of a held request beyond which it counts toward heldRequests (default 500)", example = "500") Integer slowWakeMs,
                                 @Schema(description = "Requests per second per awake replica beyond which one more replica is woken (default 0: disabled)", example = "0") Integer ratePerReplica,
                                 @Schema(description = "Replicas woken at each decision (default 1)", example = "1") Integer stepReplicas,
                                 @Schema(description = "Minimum delay (seconds) between two additional wake-up decisions (default 10)", example = "10") Integer cooldownSeconds) {
        public AdditionalWhen {
            nonNeg("inflightPerReplica", inflightPerReplica, 100000);
            nonNeg("heldRequests", heldRequests, 100000);
            nonNeg("slowWakeMs", slowWakeMs, 60000);
            nonNeg("ratePerReplica", ratePerReplica, 1000000);
            if (stepReplicas != null && (stepReplicas < 1 || stepReplicas > 100)) throw new IllegalArgumentException("sleep.wake.additionalWhen.stepReplicas: 1 to 100");
            nonNeg("cooldownSeconds", cooldownSeconds, 86400);
        }

        private static void nonNeg(String name, Integer v, int max) {
            if (v != null && (v < 0 || v > max)) throw new IllegalArgumentException("sleep.wake.additionalWhen." + name + ": 0 to " + max);
        }
    }

    /** Disabled mode (default delay values). */
    public static SleepSpec off() {
        return new SleepSpec(false, null, null, null, null);
    }
}

package io.gluonify.plasma.fermion.client;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Client configuration: {@code gluonify.fermion.client.*}. */
@ConfigMapping(prefix = "gluonify.fermion.client")
public interface FermionClientConfig {

    /** The client is inactive if {@code false} or if the URL is empty. */
    @WithDefault("true")
    boolean enabled();

    /**
     * Base URL of fermion (http(s):// or ws(s)://), without the {@code /ws/fermion} path. Provided by the platform. Empty: the address comes from
     * the {@code GluonifyServices} resolution if {@link #discover()} (developer workstation: public address of the {@code gluonify login} zone).
     */
    @WithDefault("${GLUONIFY_SERVICE_FERMION_URL:}")
    Optional<String> url();

    /**
     * Without {@link #url()}, look for fermion through {@code GluonifyServices} (override, platform, developer workstation). {@code false}: inactive
     * without an explicit URL.
     */
    @WithDefault("true")
    boolean discover();

    /** Application identifier (default: {@code quarkus.application.name}). */
    Optional<String> appId();

    /** Node identifier (default: {@code GLUONIFY_REPLICA}, otherwise {@code 0}). */
    @WithDefault("${GLUONIFY_REPLICA:0}")
    String nodeId();

    /** Base URL of the application, announced with {@code /q/openapi} (default: {@code GLUONIFY_SELF_URL}). */
    @WithDefault("${GLUONIFY_SELF_URL:}")
    Optional<String> selfUrl();

    /** Maximum number of {@code Start} messages kept during a disconnection. */
    @WithDefault("100")
    int queueSize();

    /** Period of the ping and reconnection task. */
    @WithDefault("10s")
    String reconnectInterval();

    /**
     * Period of the heartbeat ({@code progress}) sent while a task is running: fermion does not resend a task whose node has given
     * a sign of life recently. {@code 0s} disables it (fermion then relies on {@link SkillHandler#expectedDuration()} or on its own timeout).
     * The first heartbeat is sent one period after the task starts: a short task sends none.
     */
    @WithDefault("60s")
    Duration heartbeatInterval();

    /**
     * What the node can take at once and when it takes nothing, announced in {@code Hello} (fermion's capacity-aware placement).
     *
     * @return the capacity group
     */
    Capacity capacity();

    /**
     * Capacity of the node: {@code gluonify.fermion.client.capacity.*}.
     *
     * @author David CABILLIC
     */
    interface Capacity {

        /**
         * Concurrent tasks per resource name ({@code capacity.slots.heavy=1}); a skill holds them through {@link SkillHandler#requires()}.
         *
         * @return the slots, empty for none
         */
        Map<String, Integer> slots();

        /**
         * Windows during which the node takes no new task, {@code [DAYS ]HH:mm-HH:mm} ({@code 23:30-07:00}, {@code MON 00:00-06:00}).
         *
         * @return the windows
         */
        Optional<List<String>> closed();

        /**
         * Time zone of the windows.
         *
         * @return an IANA zone
         */
        @WithDefault("Europe/Paris")
        String zone();
    }
}

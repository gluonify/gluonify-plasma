package io.gluonify.plasma.fermion.client;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Configuration du client : {@code gluonify.fermion.client.*}. */
@ConfigMapping(prefix = "gluonify.fermion.client")
public interface FermionClientConfig {

    /** Le client est inactif si {@code false} ou si l'URL est vide. */
    @WithDefault("true")
    boolean enabled();

    /**
     * URL de base de fermion (http(s):// ou ws(s)://), sans le chemin {@code /ws/fermion}. Fournie par la plateforme. Vide : l'adresse vient de
     * la résolution de {@code GluonifyServices} si {@link #discover()} (poste du développeur : adresse publique de la zone de {@code gluonify login}).
     */
    @WithDefault("${GLUONIFY_SERVICE_FERMION_URL:}")
    Optional<String> url();

    /**
     * Sans {@link #url()}, chercher fermion par {@code GluonifyServices} (surcharge, plateforme, poste du développeur). {@code false} : client inactif
     * sans URL explicite.
     */
    @WithDefault("true")
    boolean discover();

    /** Identifiant de l'application (défaut : {@code quarkus.application.name}). */
    Optional<String> appId();

    /** Identifiant du nœud (défaut : {@code GLUONIFY_REPLICA}, sinon {@code 0}). */
    @WithDefault("${GLUONIFY_REPLICA:0}")
    String nodeId();

    /** URL de base de l'application, annoncée avec {@code /q/openapi} (défaut : {@code GLUONIFY_SELF_URL}). */
    @WithDefault("${GLUONIFY_SELF_URL:}")
    Optional<String> selfUrl();

    /** Nombre maximal de {@code Start} gardés pendant une coupure. */
    @WithDefault("100")
    int queueSize();

    /** Période de la tâche de ping et de reconnexion. */
    @WithDefault("10s")
    String reconnectInterval();

    /**
     * Période du battement ({@code progress}) envoyé pendant qu'une tâche s'exécute : fermion ne renvoie pas une tâche dont le nœud a donné
     * signe de vie récemment. {@code 0s} le désactive (fermion se fie alors à {@link SkillHandler#expectedDuration()} ou à son propre délai).
     * Le premier battement part une période après le début de la tâche : une tâche courte n'en envoie aucun.
     */
    @WithDefault("60s")
    Duration heartbeatInterval();
}

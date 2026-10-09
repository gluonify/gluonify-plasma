package io.gluonify.plasma.fermion.client;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/** Configuration du client : {@code gluonify.fermion.client.*}. */
@ConfigMapping(prefix = "gluonify.fermion.client")
public interface FermionClientConfig {

    /** Le client est inactif si {@code false} ou si l'URL est vide. */
    @WithDefault("true")
    boolean enabled();

    /** URL de base de fermion (http(s):// ou ws(s)://), sans le chemin {@code /ws/fermion}. Fournie par la plateforme. */
    @WithDefault("${GLUONIFY_SERVICE_FERMION_URL:}")
    Optional<String> url();

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
}

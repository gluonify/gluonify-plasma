package io.gluonify.plasma.fermion.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Identité stable du nœud : {@code appId} (l'application) et {@code nodeId} (la réplique) ; {@link #id()} = {@code appId-nodeId}. */
@ApplicationScoped
public class NodeIdentity {

    @Inject
    FermionClientConfig config;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "app")
    String applicationName;

    public String appId() {
        return config.appId().filter(s -> !s.isBlank()).orElse(applicationName);
    }

    public String nodeId() {
        return config.nodeId();
    }

    public String id() {
        return appId() + "-" + nodeId();
    }
}

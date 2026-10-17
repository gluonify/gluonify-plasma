package io.gluonify.plasma.fermion.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Stable identity of the node: {@code appId} (the application) and {@code nodeId} (the replica); {@link #id()} = {@code appId-nodeId}. */
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

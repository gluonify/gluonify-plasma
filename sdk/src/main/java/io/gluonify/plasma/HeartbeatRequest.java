package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** running : instances démarrées ; ready : sous-ensemble dont /q/health/ready est UP. */
@Schema(description = "Heartbeat du nœud.")
public record HeartbeatRequest(@Schema(description = "Noms des instances démarrées", example = "shop-0") List<String> running,
        @Schema(description = "Instances dont /q/health/ready répond UP (health check Quarkus standard)", example = "shop-0") List<String> ready) {}

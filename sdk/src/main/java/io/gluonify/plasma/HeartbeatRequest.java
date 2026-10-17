package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** running: started instances; ready: subset whose /q/health/ready is UP. */
@Schema(description = "Node heartbeat.")
public record HeartbeatRequest(@Schema(description = "Names of the started instances", example = "shop-0") List<String> running,
        @Schema(description = "Instances whose /q/health/ready answers UP (standard Quarkus health check)", example = "shop-0") List<String> ready) {}

package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Desired state of the node. */
@Schema(description = "Desired state of the node.")
public record HeartbeatResponse(@Schema(description = "Instances to run") List<Instance> desired,
        @Schema(description = "API URLs of the member control-planes (for the agent's failover)", example = "http://cp1:8081") List<String> controlPlanes) {}

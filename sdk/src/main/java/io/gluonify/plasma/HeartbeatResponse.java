package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** État désiré du nœud. */
@Schema(description = "État désiré du nœud.")
public record HeartbeatResponse(@Schema(description = "Instances à faire tourner") List<Instance> desired,
        @Schema(description = "URLs API des control-planes membres (pour le basculement de l'agent)", example = "http://cp1:8081") List<String> controlPlanes) {}

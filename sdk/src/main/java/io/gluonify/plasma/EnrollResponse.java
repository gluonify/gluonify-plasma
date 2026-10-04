package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Identité du nœud.")
public record EnrollResponse(@Schema(description = "Identifiant du nœud", example = "n-1a2b3c4d") String nodeId, @Schema(description = "Secret à présenter dans X-Node-Secret") String secret) {}

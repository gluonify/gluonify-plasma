package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Node identity.")
public record EnrollResponse(@Schema(description = "Node identifier", example = "n-1a2b3c4d") String nodeId, @Schema(description = "Secret to present in X-Node-Secret") String secret) {}

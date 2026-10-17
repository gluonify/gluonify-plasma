package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** address may be empty: the control-plane then uses the source address. */
@Schema(description = "Node enrollment request.")
public record EnrollRequest(@Schema(description = "Single-use enrollment token", example = "e6f1fa7a3228b8b0") String token, @Schema(description = "Advertised address; empty = source address", example = "10.0.0.12") String address, @Schema(description = "Memory allocatable to apps (MB)", example = "1024") int memoryMb, @Schema(description = "Node architecture: amd64 or arm64 (absent: old node, receives only apps without \"artifacts\")", example = "arm64") String arch) {}

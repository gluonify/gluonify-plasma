package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** address peut être vide : le control-plane utilise alors l'adresse source. */
@Schema(description = "Demande d'enrôlement d'un nœud.")
public record EnrollRequest(@Schema(description = "Jeton d'enrôlement à usage unique", example = "e6f1fa7a3228b8b0") String token, @Schema(description = "Adresse annoncée ; vide = adresse source", example = "10.0.0.12") String address, @Schema(description = "Mémoire allouable aux apps (Mo)", example = "1024") int memoryMb, @Schema(description = "Architecture du nœud : amd64 ou arm64 (absente : nœud ancien, ne reçoit que les applis sans « artifacts »)", example = "arm64") String arch) {}

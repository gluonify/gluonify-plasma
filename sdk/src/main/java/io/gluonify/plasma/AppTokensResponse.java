package io.gluonify.plasma;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Jetons à courte durée (émis par gluonify-charm) : un par application autorisée sur ce nœud. */
@Schema(description = "Jetons à courte durée, par application.")
public record AppTokensResponse(@Schema(description = "Application -> jeton") Map<String, AppToken> tokens) {
    @Schema(description = "Jeton d'une application.")
    public record AppToken(@Schema(description = "JWT à présenter au top (Authorization: Bearer)") String token,
                           @Schema(description = "Expiration (ms depuis l'epoch)", example = "1700000300000") long expiresAt) {}
}

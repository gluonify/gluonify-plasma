package io.gluonify.plasma;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Short-lived tokens (issued by gluonify-charm): one per application allowed on this node. */
@Schema(description = "Short-lived tokens, per application.")
public record AppTokensResponse(@Schema(description = "Application -> token") Map<String, AppToken> tokens) {
    @Schema(description = "Token of an application.")
    public record AppToken(@Schema(description = "JWT to present to the top (Authorization: Bearer)") String token,
                           @Schema(description = "Expiration (ms since the epoch)", example = "1700000300000") long expiresAt) {}
}

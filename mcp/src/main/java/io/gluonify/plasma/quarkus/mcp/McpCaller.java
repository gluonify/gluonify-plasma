package io.gluonify.plasma.quarkus.mcp;

import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The verified caller of {@code /mcp} (Prism, with its application-to-service Charm token), given to a tool that declares a parameter of this type.
 *
 * @param subject the token's subject ({@code app:prism})
 * @param roles   the roles of the token ({@code roles} claim and the identity's roles)
 * @param token   the verified token, for other claims (never log its raw value); empty in unit tests of the protocol
 */
public record McpCaller(String subject, Set<String> roles, Optional<JsonWebToken> token) {
    public McpCaller {
        roles = Set.copyOf(roles);
        token = token == null ? Optional.empty() : token;
    }

    public boolean hasRole(final String role) {
        return roles.contains(role);
    }

    @Override
    public String toString() { // the token never appears in a log
        return "McpCaller[subject=" + subject + ", roles=" + roles + "]";
    }
}

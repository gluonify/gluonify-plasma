package io.gluonify.plasma;

import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Application-to-service token: an application that declares {@code "uses": ["crystallon"]} receives a short-lived Charm token
 * (audience and roles set by the control-plane policy, for example {@code gluonify-crystallon} and {@code gluonify:crystallon:render}),
 * dropped by the agent in a file re-read on every call and renewed before it expires.
 * <p>Conventions shared by the control-plane, the agent and the SDK clients:
 * <ul>
 *   <li>the file is {@link #DIR}{@code /<service>.token} in the instance's sandbox ({@link #fileName(String)});</li>
 *   <li>its path is given to the application by the variable {@code GLUONIFY_<SERVICE>_TOKEN_FILE} ({@link #envName(String)}), which the SDK client
 *       reads (for example {@code CrystallonTokens.platform()}); an application cannot impose it ({@link #isReservedEnv(String)}).</li>
 * </ul>
 *
 * @param service   name of the service application, as written in {@code uses} (without the "?" of optional services)
 * @param audience  token audience
 * @param token     the JWT, to be presented as {@code Authorization: Bearer}; never logged
 * @param expiresAt expiration (ms since the epoch)
 */
@Schema(description = "Application-to-service token (Charm), for a service declared in uses.")
public record ServiceToken(@Schema(description = "Target service (name in uses)", example = "crystallon") String service,
                           @Schema(description = "Token audience", example = "gluonify-crystallon") String audience,
                           @Schema(description = "JWT to present to the service (Authorization: Bearer)") String token,
                           @Schema(description = "Expiration (ms since the epoch)", example = "1700000900000") long expiresAt) {

    /** Token directory in an instance's sandbox (mounted read-only by the agent). */
    public static final String DIR = "/run/gluonify-tokens";

    /** {@code crystallon} -> {@code GLUONIFY_CRYSTALLON_TOKEN_FILE} ; {@code my-svc} -> {@code GLUONIFY_MY_SVC_TOKEN_FILE}. */
    public static String envName(final String service) {
        return "GLUONIFY_" + service.toUpperCase(Locale.ROOT).replace('-', '_') + "_TOKEN_FILE";
    }

    /** {@code crystallon} -> {@code crystallon.token}. */
    public static String fileName(final String service) {
        return service + ".token";
    }

    /** True for {@code GLUONIFY_<X>_TOKEN_FILE}: variable provided by the platform, never by the application. */
    public static boolean isReservedEnv(final String name) {
        return name != null && name.startsWith("GLUONIFY_") && name.endsWith("_TOKEN_FILE") && name.length() > "GLUONIFY__TOKEN_FILE".length();
    }

    @Override
    public String toString() { // the token never appears in a log
        return "ServiceToken[service=" + service + ", audience=" + audience + ", expiresAt=" + expiresAt + "]";
    }
}

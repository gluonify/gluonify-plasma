package io.gluonify.plasma;

import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Jeton d'application vers service : une application qui déclare {@code "uses": ["crystallon"]} reçoit un jeton Charm à courte durée
 * (audience et rôles fixés par la politique du control-plane, par exemple {@code gluonify-crystallon} et {@code gluonify:crystallon:render}),
 * déposé par l'agent dans un fichier relu à chaque appel et renouvelé avant son expiration.
 * <p>Conventions partagées par le control-plane, l'agent et les clients du SDK :
 * <ul>
 *   <li>le fichier est {@link #DIR}{@code /<service>.token} dans le bac à sable de l'instance ({@link #fileName(String)}) ;</li>
 *   <li>son chemin est donné à l'application par la variable {@code GLUONIFY_<SERVICE>_TOKEN_FILE} ({@link #envName(String)}), que le client du SDK
 *       lit (par exemple {@code CrystallonTokens.platform()}) ; une application ne peut pas l'imposer ({@link #isReservedEnv(String)}).</li>
 * </ul>
 *
 * @param service   nom de l'application de service, tel qu'écrit dans {@code uses} (sans le « ? » des services facultatifs)
 * @param audience  audience du jeton
 * @param token     le JWT, à présenter en {@code Authorization: Bearer} ; jamais journalisé
 * @param expiresAt expiration (ms depuis l'epoch)
 */
@Schema(description = "Jeton d'application vers service (Charm), pour un service déclaré dans uses.")
public record ServiceToken(@Schema(description = "Service visé (nom dans uses)", example = "crystallon") String service,
                           @Schema(description = "Audience du jeton", example = "gluonify-crystallon") String audience,
                           @Schema(description = "JWT à présenter au service (Authorization: Bearer)") String token,
                           @Schema(description = "Expiration (ms depuis l'epoch)", example = "1700000900000") long expiresAt) {

    /** Dossier des jetons dans le bac à sable d'une instance (monté en lecture seule par l'agent). */
    public static final String DIR = "/run/gluonify-tokens";

    /** {@code crystallon} -> {@code GLUONIFY_CRYSTALLON_TOKEN_FILE} ; {@code my-svc} -> {@code GLUONIFY_MY_SVC_TOKEN_FILE}. */
    public static String envName(String service) {
        return "GLUONIFY_" + service.toUpperCase(Locale.ROOT).replace('-', '_') + "_TOKEN_FILE";
    }

    /** {@code crystallon} -> {@code crystallon.token}. */
    public static String fileName(String service) {
        return service + ".token";
    }

    /** Vrai pour {@code GLUONIFY_<X>_TOKEN_FILE} : variable fournie par la plateforme, jamais par l'application. */
    public static boolean isReservedEnv(String name) {
        return name != null && name.startsWith("GLUONIFY_") && name.endsWith("_TOKEN_FILE") && name.length() > "GLUONIFY__TOKEN_FILE".length();
    }

    @Override
    public String toString() { // le jeton n'apparaît jamais dans un journal
        return "ServiceToken[service=" + service + ", audience=" + audience + ", expiresAt=" + expiresAt + "]";
    }
}

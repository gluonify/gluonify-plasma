package io.gluonify.plasma.crystallon.client;

/**
 * Échec d'un appel à Crystallon. Les erreurs de Crystallon (corps {@code ErrorBody}) donnent {@link #status()}, {@link #code()} (par exemple
 * {@code CRYSTALLON-0014}), {@link #getMessage()} (le message du serveur, en anglais) et {@link #path()} (l'endroit du problème dans le JSON du
 * modèle, quand il y en a un).
 *
 * <ul>
 *   <li>un 401 ou un 403 sans corps (jeton absent, expiré, ou sans le rôle {@code gluonify:crystallon:render}) : {@code code()} est {@code null} ;</li>
 *   <li>une réponse qui n'est pas un PDF, ou un PDF plus grand que la limite : {@code status()} est le statut reçu, {@code code()} est {@code null} ;</li>
 *   <li>aucune réponse (service injoignable, délai dépassé, appel interrompu) : {@code status()} vaut 0.</li>
 * </ul>
 */
public class CrystallonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final String path;

    public CrystallonException(final int status, final String code, final String message, final String path) {
        this(status, code, message, path, null);
    }

    public CrystallonException(final int status, final String code, final String message, final String path, final Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.path = path;
    }

    /** @return le statut HTTP de la réponse, 0 quand il n'y a pas eu de réponse */
    public int status() {
        return status;
    }

    /** @return le code d'erreur de Crystallon, ou {@code null} (401/403 sans corps, réponse inattendue, pas de réponse) */
    public String code() {
        return code;
    }

    /** @return l'endroit du problème dans le JSON du modèle, ou {@code null} */
    public String path() {
        return path;
    }
}

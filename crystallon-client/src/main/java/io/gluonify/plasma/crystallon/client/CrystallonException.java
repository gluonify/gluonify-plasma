package io.gluonify.plasma.crystallon.client;

/**
 * Failure of a call to Crystallon. Crystallon errors ({@code ErrorBody} body) provide {@link #status()}, {@link #code()} (for example
 * {@code CRYSTALLON-0014}), {@link #getMessage()} (the server message, in English) and {@link #path()} (the location of the problem in the template
 * JSON, when there is one).
 *
 * <ul>
 *   <li>a 401 or 403 without a body (token missing, expired, or without the role {@code gluonify:crystallon:render}): {@code code()} is {@code null};</li>
 *   <li>a response that is not a PDF, or a PDF larger than the limit: {@code status()} is the status received, {@code code()} is {@code null};</li>
 *   <li>no response (service unreachable, timeout, interrupted call): {@code status()} is 0.</li>
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

    /** @return the HTTP status of the response, 0 when there was no response */
    public int status() {
        return status;
    }

    /** @return the Crystallon error code, or {@code null} (401/403 without a body, unexpected response, no response) */
    public String code() {
        return code;
    }

    /** @return the location of the problem in the template JSON, or {@code null} */
    public String path() {
        return path;
    }
}

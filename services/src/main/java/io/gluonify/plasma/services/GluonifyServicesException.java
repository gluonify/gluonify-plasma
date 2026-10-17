package io.gluonify.plasma.services;

/**
 * A Gluonify service cannot be reached: not declared, no known cluster, developer session expired, discovery unreachable...
 * The message (in English) always says what to do; it never contains a token.
 */
public class GluonifyServicesException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public GluonifyServicesException(final String message) {
        super(message);
    }

    public GluonifyServicesException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

package io.gluonify.plasma.fermion.client;

/**
 * Failure of a skill with a machine-readable {@code errorKind}: fermion uses it to route the workflow. Any other exception from a
 * handler is reported without {@code errorKind}.
 */
public class SkillFailureException extends RuntimeException {

    private final String errorKind;

    public SkillFailureException(final String errorKind, final String message) {
        super(message);
        this.errorKind = errorKind;
    }

    public SkillFailureException(final String errorKind, final String message, final Throwable cause) {
        super(message, cause);
        this.errorKind = errorKind;
    }

    public String errorKind() {
        return errorKind;
    }
}

package io.gluonify.plasma.fermion.client;

/**
 * Échec d'une compétence avec un {@code errorKind} lisible par la machine : fermion s'en sert pour aiguiller le workflow. Toute autre exception d'un
 * handler est rapportée sans {@code errorKind}.
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

package io.gluonify.plasma.services;

/**
 * Un service de Gluonify ne peut pas être joint : pas déclaré, pas de cluster connu, session du développeur expirée, découverte injoignable…
 * Le message (en anglais) dit toujours quoi faire ; il ne contient jamais de jeton.
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

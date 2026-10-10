package io.gluonify.plasma.crystallon.client;

/**
 * Passerelle JSON du client : écrit le payload d'un document et lit les réponses de Crystallon. Le module n'impose pas de bibliothèque JSON :
 * {@link #detect()} prend celle qui est sur le chemin de classes de l'application, Jackson 3 d'abord, sinon Jackson 2. Une application qui
 * veut son propre {@code ObjectMapper} (modules, nommage...) le passe à {@link Jackson3Codec#of} ou {@link Jackson2Codec#of}, ou implémente
 * ces deux méthodes, puis appelle {@code CrystallonClient.builder().json(codec)}.
 *
 * <p>Le payload est un record ou un bean annoté pour Jackson (le code généré par l'éditeur de Crystallon l'est), ou une {@code Map}. Les dates sont
 * écrites en ISO-8601 (jamais en horodatage), les {@code BigDecimal} en notation décimale simple (jamais {@code 1E+3}).
 */
public interface PayloadCodec {

    /** @return le JSON (UTF-8) de la valeur @throws IllegalArgumentException si la valeur n'est pas sérialisable */
    byte[] write(Object value);

    /** @return la valeur lue ; les propriétés inconnues sont ignorées @throws IllegalArgumentException si le JSON est invalide */
    <T> T read(byte[] json, Class<T> type);

    /**
     * Détecte la bibliothèque JSON à l'exécution : {@code tools.jackson.databind.json.JsonMapper} (Jackson 3) d'abord, sinon
     * {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2, avec {@code JavaTimeModule} s'il est présent : sans lui, Jackson 2 refuse
     * les {@code LocalDate}).
     *
     * @throws IllegalStateException si aucune des deux n'est présente
     */
    static PayloadCodec detect() {
        if (present("tools.jackson.databind.json.JsonMapper")) {
            return Jackson3Codec.create();
        }
        if (present("com.fasterxml.jackson.databind.ObjectMapper")) {
            return Jackson2Codec.create();
        }
        throw new IllegalStateException("No JSON library for the Crystallon client: add Jackson 3 (tools.jackson.core:jackson-databind) or Jackson 2 "
                + "(com.fasterxml.jackson.core:jackson-databind, and com.fasterxml.jackson.datatype:jackson-datatype-jsr310 for dates), "
                + "or give your own PayloadCodec to CrystallonClient.builder().json(...)");
    }

    /** Présence d'une classe, sans la charger plus que nécessaire (littéral constant : reconnu par l'image native). */
    private static boolean present(final String className) {
        try {
            Class.forName(className);
            return true;
        }
        catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}

package io.gluonify.plasma;

import java.util.Set;

/**
 * Bolt de gDown ouvert aux applications qui déclarent {@code uses: gdown} (décision du 2026-10-13 ; conception : gluonify-gdown/paas.md, « Bolt pour les applications »).
 * Contrat entre le control-plane (qui émet le certificat et annonce l'adresse) et l'agent (qui pose les variables, le fichier de l'autorité et la règle réseau).
 */
public final class GdownBolt {
    private GdownBolt() {}

    /** Port Bolt de gDown : port supplémentaire déclaré, au même numéro sur le nœud ; TLS obligatoire. */
    public static final int PORT = 7687;
    /** Clé de la table {@code services} de l'état désiré : {@code bolt+s://<adresse du nœud>:7687} d'une instance prête de gdown (le « : » n'est pas permis dans un nom d'application). */
    public static final String SERVICE_KEY = "gdown:bolt";
    /** Variable posée dans les applications qui utilisent gdown : adresse Bolt chiffrée ({@code bolt+s://hôte:7687}). */
    public static final String URL_ENV = "GLUONIFY_GDOWN_BOLT_URL";
    /** Variable posée dans les applications qui utilisent gdown : chemin du fichier PEM de l'autorité interne de la plateforme (Bolt et HTTPS de gDown ; lue par le SDK Python). */
    public static final String CA_FILE_ENV = "GLUONIFY_GDOWN_CA_FILE";
    /** Secret de l'état désiré (public, envoyé avec les secrets parce qu'il ne passe que par le tunnel) : certificat PEM de l'autorité interne, en base64 sur une ligne. */
    public static final String CA_SECRET = "GLUONIFY_INTERNAL_CA_CERT";
    /** Dossier de l'hôte (tmpfs) où l'agent écrit l'autorité ; lié en lecture seule au même chemin dans le bac à sable des instances. */
    public static final String CA_DIR = "/run/gluonify-ca";
    /** Fichier de l'autorité vu par l'instance. */
    public static final String CA_FILE = CA_DIR + "/gluonify-internal-ca.pem";

    /** Variables posées par la plateforme : jamais fixées par une application. */
    public static final Set<String> RESERVED_ENV = Set.of(URL_ENV, CA_FILE_ENV);

    /** Vrai si l'application utilise gdown (obligatoire ou facultatif). */
    public static boolean usesGdown(java.util.List<String> uses) {
        return uses != null && (uses.contains("gdown") || uses.contains("?gdown"));
    }
}

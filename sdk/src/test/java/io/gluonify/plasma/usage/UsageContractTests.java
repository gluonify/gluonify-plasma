package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test commun du contrat d'usage {@code gluonify-usage/1} ({@code usage-mcp.md} § 6.1), publié dans le jar de tests du SDK : chaque composant en hérite
 * et ne fournit que l'accès à son point d'accès {@code /q/usage} (réponse avec le bon rôle, statut sans jeton et avec un jeton sans rôle, valeurs secrètes).
 */
public abstract class UsageContractTests {
    /** Le corps de {@code GET /q/usage} avec un jeton portant le rôle de lecture. */
    protected abstract byte[] authorizedBody();

    /** Le statut HTTP de {@code GET /q/usage} sans aucun jeton. */
    protected abstract int statusWithoutToken();

    /** Le statut HTTP de {@code GET /q/usage} avec un jeton valide mais sans le rôle d'usage. */
    protected abstract int statusWithoutRole();

    /**
     * Les valeurs de configuration sensibles du composant (mots de passe, clés, jetons, tels que les tests les connaissent) : aucune ne doit figurer dans la
     * réponse. Abstraite à dessein : chaque composant doit la fournir, et {@link #aucunSecret()} refuse une liste vide (le contrôle serait tautologique).
     */
    protected abstract Collection<String> secrets();

    @Test
    void reponseConforme() {
        assertEquals(List.of(), UsageContract.check(authorizedBody()));
    }

    @Test
    void jamaisAnonyme() {
        assertEquals(401, statusWithoutToken());
    }

    @Test
    void roleExige() {
        assertEquals(403, statusWithoutRole());
    }

    @Test
    void tailleBornee() {
        assertTrue(authorizedBody().length <= UsageContract.MAX_BYTES);
    }

    @Test
    void aucunSecret() {
        final Collection<String> secrets = secrets();
        assertTrue(secrets != null && !secrets.isEmpty(), "secrets() doit donner au moins une valeur sensible du composant");
        final String body = new String(authorizedBody(), StandardCharsets.UTF_8);
        for (final String secret : secrets) {
            assertFalse(secret == null || secret.isBlank(), "une valeur de secrets() est vide");
            assertFalse(body.contains(secret), "une valeur secrète figure dans la réponse");
        }
    }
}

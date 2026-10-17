package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Common test of the usage contract {@code gluonify-usage/1} ({@code usage-mcp.md} § 6.1), published in the SDK's test jar: each component inherits it
 * and only provides access to its {@code /q/usage} endpoint (response with the right role, status without a token and with a token without the role, secret values).
 */
public abstract class UsageContractTests {
    /** The body of {@code GET /q/usage} with a token carrying the read role. */
    protected abstract byte[] authorizedBody();

    /** The HTTP status of {@code GET /q/usage} without any token. */
    protected abstract int statusWithoutToken();

    /** The HTTP status of {@code GET /q/usage} with a valid token but without the usage role. */
    protected abstract int statusWithoutRole();

    /**
     * The component's sensitive configuration values (passwords, keys, tokens, as the tests know them): none may appear in the
     * response. Abstract on purpose: each component must provide it, and {@link #noSecret()} refuses an empty list (the check would be tautological).
     */
    protected abstract Collection<String> secrets();

    /**
     * True for a component that stores data (Rup, gDown…): its report must then carry the top-level {@code storageBytes}. Default false: a component that stores
     * nothing omits it, and an older report without it stays valid.
     */
    protected boolean storesData() {
        return false;
    }

    @Test
    void conformingResponse() {
        assertEquals(List.of(), UsageContract.check(authorizedBody()));
    }

    @Test
    void neverAnonymous() {
        assertEquals(401, statusWithoutToken());
    }

    @Test
    void roleRequired() {
        assertEquals(403, statusWithoutRole());
    }

    @Test
    void boundedSize() {
        assertTrue(authorizedBody().length <= UsageContract.MAX_BYTES);
    }

    /** Storage (additive, 2026-10-17): declared when {@link #storesData()}; non-negative integers; the rows' sum is at most the total. */
    @Test
    void storageIsConsistent() {
        final JsonNode root = JsonMapper.builder().build().readTree(new String(authorizedBody(), StandardCharsets.UTF_8));
        final JsonNode total = root.get("storageBytes");
        if (storesData()) {
            assertTrue(total != null && total.isIntegralNumber() && total.asLong() >= 0, "a component that stores data reports storageBytes (integer >= 0)");
        }
        long rows = 0;
        for (final JsonNode row : root.path("byApp")) {
            final JsonNode v = row.get("storageBytes");
            if (v != null && !v.isNull()) {
                assertTrue(v.isIntegralNumber() && v.asLong() >= 0, "byApp storageBytes: integer >= 0");
                assertTrue(total != null && total.isIntegralNumber(), "rows carry storageBytes: the total is required");
                rows += v.asLong();
            }
        }
        if (total != null && total.isIntegralNumber()) {
            assertTrue(rows <= total.asLong(), "sum of byApp.storageBytes <= storageBytes");
        }
    }

    @Test
    void noSecret() {
        final Collection<String> secrets = secrets();
        assertTrue(secrets != null && !secrets.isEmpty(), "secrets() must give at least one sensitive value of the component");
        final String body = new String(authorizedBody(), StandardCharsets.UTF_8);
        for (final String secret : secrets) {
            assertFalse(secret == null || secret.isBlank(), "a value of secrets() is empty");
            assertFalse(body.contains(secret), "a secret value appears in the response");
        }
    }
}

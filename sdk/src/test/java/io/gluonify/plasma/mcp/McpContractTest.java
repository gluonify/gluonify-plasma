package io.gluonify.plasma.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McpContractTest {

    @Test
    void toolNameGrammar() {
        assertTrue(McpContract.isValidToolName("a"));
        assertTrue(McpContract.isValidToolName("order_stats2"));
        assertTrue(McpContract.isValidToolName("a" + "b".repeat(40)));
        assertFalse(McpContract.isValidToolName("a" + "b".repeat(41)));
        assertFalse(McpContract.isValidToolName("Order"));
        assertFalse(McpContract.isValidToolName("2fa"));
        assertFalse(McpContract.isValidToolName("_x"));
        assertFalse(McpContract.isValidToolName("a-b"));
        assertFalse(McpContract.isValidToolName("app.shop.x"));
        assertFalse(McpContract.isValidToolName(""));
        assertFalse(McpContract.isValidToolName(null));
    }

    @Test
    void limitsAndNegotiation() {
        assertEquals(50, McpContract.MAX_TOOLS);
        assertEquals(16384, McpContract.MAX_SCHEMA_BYTES);
        assertEquals(65536, McpContract.MAX_ARGUMENTS_BYTES);
        assertEquals(262144, McpContract.MAX_RESPONSE_BYTES);
        assertEquals(69632, McpContract.MAX_BODY_BYTES);
        assertEquals("2025-03-26", McpContract.negotiate("2025-03-26"));
        assertEquals("2024-11-05", McpContract.negotiate("2024-11-05"));
        assertEquals("2025-06-18", McpContract.negotiate("1999-01-01"));
        assertEquals("2025-06-18", McpContract.negotiate(null));
        assertEquals(2, McpContract.utf8Size("é"));
    }
}

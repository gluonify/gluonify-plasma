package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrganizationsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void identifiers() {
        for (String ok : new String[] {"acme", "a", "globex-2", "0", "a".repeat(40)}) assertTrue(Organizations.isValid(ok), ok);
        for (String bad : new String[] {"", "Acme", "a_b", "a b", "a.b", "a".repeat(41), Organizations.UNASSIGNED, "-acme", "acme-", "-"}) assertFalse(Organizations.isValid(bad), bad);
        assertFalse(Organizations.isValid(null));
        assertDoesNotThrow(() -> Organizations.requireValid(null));
        assertThrows(IllegalArgumentException.class, () -> Organizations.requireValid("_unassigned"));
    }

    @Test
    void displayAndFilter() {
        assertEquals("_unassigned", Organizations.orUnassigned(null));
        assertEquals("acme", Organizations.orUnassigned("acme"));
        assertTrue(Organizations.matches(null, null));
        assertTrue(Organizations.matches(null, "acme"));
        assertTrue(Organizations.matches("acme", "acme"));
        assertFalse(Organizations.matches("acme", "globex"));
        assertFalse(Organizations.matches("acme", null));
        assertTrue(Organizations.matches("_unassigned", null));
        assertFalse(Organizations.matches("_unassigned", "acme"));
    }

    @Test
    void customerRoles() {
        assertEquals(java.util.List.of("org:owner", "org:developer", "org:viewer", "org:billing"), Organizations.ROLES);
        assertTrue(Organizations.isCustomerRole("org:viewer"));
        assertFalse(Organizations.isCustomerRole("gluonify:higgs:apps:read"));
        assertFalse(Organizations.isCustomerRole(null));
        for (String r : Organizations.ROLES) assertFalse(r.startsWith("gluonify:"), "never under the reserved prefix: " + r);
    }

    @Test
    void appSpecOrganization() {
        var s = new AppSpec("shop", "img", 1, 8080, 64, null);
        assertNull(s.organization(), "absent by default");
        assertEquals("_unassigned", s.organizationOrUnassigned());
        var a = s.withOrganization("acme");
        assertEquals("acme", a.organization());
        assertEquals("acme", a.organizationOrUnassigned());
        assertEquals(s, a.withOrganization(null), "the copy only changes the organization");
        assertThrows(IllegalArgumentException.class, () -> s.withOrganization("ACME"));
        assertThrows(IllegalArgumentException.class, () -> s.withOrganization("_unassigned"));
        assertEquals("acme", new DeployRequest("img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "acme").toSpec("shop").organization());
        assertNull(new DeployRequest("img", null, null, null, null, null, null).toSpec("shop").organization());
        var retrait = new DeployRequest("img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "_unassigned");
        assertEquals("_unassigned", retrait.organization(), "la demande garde la valeur (retrait explicite)");
        assertNull(retrait.toSpec("shop").organization(), "the specification has none");
    }

    @Test
    void compatibleJson() throws Exception {
        var ancien = json.readValue("{\"name\":\"shop\",\"image\":\"img\"}", AppSpec.class);
        assertNull(ancien.organization(), "a state without the field can be read back");
        var a = new AppSpec("shop", "img", 1, 8080, 64, null).withOrganization("acme");
        String txt = json.writeValueAsString(a);
        assertTrue(txt.contains("\"organization\":\"acme\""), txt);
        assertFalse(txt.contains("organizationOrUnassigned"), txt);
        assertEquals(a, json.readValue(txt, AppSpec.class));
        assertEquals("acme", json.readValue("{\"image\":\"img\",\"organization\":\"acme\"}", DeployRequest.class).organization());
        assertThrows(Exception.class, () -> json.readValue("{\"name\":\"shop\",\"image\":\"img\",\"organization\":\"Bad Org\"}", AppSpec.class));
    }
}

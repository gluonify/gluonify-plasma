package io.gluonify.plasma.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.access.CustomerAccess.Customer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The customer-token rule of subscriptions.md § 3.2, with a table shaped like Crystallon's. */
class CustomerAccessTest {
    static final CustomerAccess.Table TABLE = CustomerAccess.Table.builder()
            .grant("org:owner", "svc:write", "svc:render")
            .grant("org:developer", "svc:write", "svc:render")
            .grant("org:viewer", "svc:read", "svc:render")
            .build();

    @Test
    void aTokenWithAnOrganizationAndNoPlatformRoleIsACustomer() {
        final Customer c = CustomerAccess.recognise("acme", List.of("org:developer"), TABLE).orElseThrow();
        assertEquals("acme", c.organization());
        assertEquals(Set.of("org:developer"), c.customerRoles());
        assertEquals(Set.of("svc:write", "svc:render"), c.rights());
    }

    @Test
    void eachCustomerRoleGetsItsColumnOnly() {
        assertEquals(Set.of("svc:write", "svc:render"), rights("org:owner"));
        assertEquals(Set.of("svc:read", "svc:render"), rights("org:viewer"));
        assertEquals(Set.of(), rights("org:billing"), "billing: no console");
        assertEquals(Set.of("svc:write", "svc:render", "svc:read"), CustomerAccess.recognise("acme", List.of("org:viewer", "org:owner"), TABLE).orElseThrow().rights());
    }

    static Set<String> rights(final String role) {
        return CustomerAccess.recognise("acme", List.of(role), TABLE).orElseThrow().rights();
    }

    @Test
    void anyPlatformRoleMakesItAPlatformToken() {
        assertEquals(Optional.empty(), CustomerAccess.recognise("acme", List.of("org:owner", "gluonify:svc:read"), TABLE));
        assertEquals(Optional.empty(), CustomerAccess.recognise("acme", List.of("gluonify:admin"), TABLE));
        assertTrue(CustomerAccess.hasPlatformRole(List.of("gluonify:x")));
        assertFalse(CustomerAccess.hasPlatformRole(List.of("org:owner", "gluonifyx")));
    }

    @Test
    void withoutAValidOrganizationThereIsNoCustomer() {
        assertEquals(Optional.empty(), CustomerAccess.recognise(null, List.of("org:owner"), TABLE));
        assertEquals(Optional.empty(), CustomerAccess.recognise("", List.of("org:owner"), TABLE));
        assertEquals(Optional.empty(), CustomerAccess.recognise("Acme", List.of("org:owner"), TABLE), "not a DNS label");
        assertEquals(Optional.empty(), CustomerAccess.recognise("a".repeat(41), List.of("org:owner"), TABLE), "longer than Charm allows");
        assertEquals(Optional.empty(), CustomerAccess.recognise(List.of("acme", "beta"), List.of("org:owner"), TABLE), "two organizations");
        assertEquals(Optional.empty(), CustomerAccess.recognise(42, List.of("org:owner"), TABLE));
        assertEquals(Optional.of("acme"), CustomerAccess.organization(List.of("acme")));
        assertEquals(Optional.of("acme"), CustomerAccess.organization(" acme "));
    }

    @Test
    void unknownRolesGrantNothing() {
        final Customer c = CustomerAccess.recognise("acme", List.of("org:admin", "svc:write", "someone:else"), TABLE).orElseThrow();
        assertEquals(Set.of(), c.customerRoles());
        assertEquals(Set.of(), c.rights(), "a service right in the token is not taken from the token");
        assertEquals(Set.of(), CustomerAccess.recognise("acme", null, TABLE).orElseThrow().rights());
    }

    @Test
    void theTableAcceptsCustomerRolesAndServiceRightsOnly() {
        assertThrows(IllegalArgumentException.class, () -> CustomerAccess.Table.builder().grant("org:admin", "svc:write"));
        assertThrows(IllegalArgumentException.class, () -> CustomerAccess.Table.builder().grant("gluonify:svc:write", "svc:write"));
        assertThrows(IllegalArgumentException.class, () -> CustomerAccess.Table.builder().grant("org:viewer", "org:owner"));
        assertThrows(IllegalArgumentException.class, () -> CustomerAccess.Table.builder().grant("org:viewer", " "));
        assertEquals(Set.of(), TABLE.rightsOf("org:billing"));
    }
}

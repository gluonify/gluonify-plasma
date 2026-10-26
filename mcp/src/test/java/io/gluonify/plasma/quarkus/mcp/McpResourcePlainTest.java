package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.mcp.McpContract;
import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InjectableBean;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The {@code /mcp} resource as a plain object: audiences, who is the relay, what the tools see of the caller, and the HTTP answers, without a running application. */
class McpResourcePlainTest {
    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    public static class Tools implements McpToolbox {
        @McpTool(description = "who calls")
        public String whoAmI(final McpCaller caller) {
            return caller.subject() + " " + new java.util.TreeSet<>(caller.roles());
        }
    }

    /** A stand-in for the container's client proxy of a toolbox. */
    static final class ToolsProxy implements ClientProxy {
        final Object delegate;

        ToolsProxy(final Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object arc_contextualInstance() {
            return delegate;
        }

        @Override
        public InjectableBean<?> arc_bean() {
            return null;
        }
    }

    static JsonWebToken jwt(final String subject, final Map<String, Object> claims) {
        return (JsonWebToken) Proxy.newProxyInstance(McpResourcePlainTest.class.getClassLoader(), new Class<?>[]{JsonWebToken.class}, (p, m, a) -> switch (m.getName()) {
            case "getClaim" -> claims.get((String) a[0]);
            case "getSubject", "getName" -> subject;
            case "getClaimNames" -> claims.keySet();
            case "toString" -> "jwt";
            case "hashCode" -> 1;
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    static SecurityIdentity identity(final boolean anonymous, final Principal principal, final Set<String> roles) {
        return (SecurityIdentity) Proxy.newProxyInstance(McpResourcePlainTest.class.getClassLoader(), new Class<?>[]{SecurityIdentity.class}, (p, m, a) -> switch (m.getName()) {
            case "isAnonymous" -> anonymous;
            case "getPrincipal" -> principal;
            case "getRoles" -> roles;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @SuppressWarnings("unchecked")
    static <T> Instance<T> instance(final List<T> beans) {
        return (Instance<T>) Proxy.newProxyInstance(McpResourcePlainTest.class.getClassLoader(), new Class<?>[]{Instance.class}, (p, m, a) -> switch (m.getName()) {
            case "iterator" -> beans.iterator();
            case "forEach" -> {
                beans.forEach((java.util.function.Consumer<? super T>) a[0]);
                yield null;
            }
            case "isResolvable" -> !beans.isEmpty();
            case "get" -> beans.get(0);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    static McpResource resource(final SecurityIdentity identity, final Object... toolboxes) {
        final McpResource r = new McpResource();
        r.toolboxes = instance(List.of(toolboxes).stream().map(McpToolbox.class::cast).toList());
        r.mappers = instance(List.<ObjectMapper>of());
        r.identity = identity;
        r.audience = Optional.empty();
        r.relayApp = "prism";
        r.oidcAudience = Optional.empty();
        r.applicationName = "shop";
        r.applicationVersion = "1.0";
        return r;
    }

    static final Map<String, Object> RELAY = Map.of("aud", List.of("gluonify-shop"), "gluonify_kind", "service", "gluonify_app", "prism", "roles", List.of("gluonify:mcp:write"));

    // --- audiences ---

    @Test
    void theAudienceIsTheConfiguredOneThenTheOidcOnesThenTheBrandedApplicationName() {
        final McpResource r = resource(null);
        assertEquals(Set.of("gluonify-shop"), r.audiences());
        r.applicationName = "gluonify-already";
        assertEquals(Set.of("gluonify-already"), r.audiences(), "the brand prefix is not doubled");
        r.oidcAudience = Optional.of(List.of("a", "b"));
        assertEquals(Set.of("a", "b"), r.audiences());
        r.oidcAudience = Optional.of(List.of());
        assertEquals(Set.of("gluonify-already"), r.audiences(), "an empty list is no list");
        r.audience = Optional.of("  explicit ");
        assertEquals(Set.of("explicit"), r.audiences());
        r.audience = Optional.of("  ");
        assertEquals(Set.of("gluonify-already"), r.audiences(), "a blank audience is no audience");
    }

    // --- identity ---

    @Test
    void onlyAVerifiedJwtOfTheApplicationsAudienceIsAToken() {
        assertNull(resource(null).token());
        assertNull(resource(identity(true, jwt("x", RELAY), Set.of())).token(), "anonymous");
        assertNull(resource(identity(false, () -> "plain principal", Set.of())).token(), "not a JWT");
        assertNull(resource(identity(false, jwt("x", Map.of("aud", "gluonify-other")), Set.of())).token(), "another audience");
        assertNull(resource(identity(false, jwt("x", Map.of()), Set.of())).token(), "no audience claim");
        final JsonWebToken ok = jwt("app:prism", RELAY);
        assertSame(ok, resource(identity(false, ok, Set.of())).token());
        final JsonWebToken single = jwt("app:prism", Map.of("aud", "gluonify-shop"));
        assertSame(single, resource(identity(false, single, Set.of())).token(), "a single string audience");
        final JsonWebToken quoted = jwt("app:prism", Map.of("aud", List.of("\"gluonify-shop\"")));
        assertSame(quoted, resource(identity(false, quoted, Set.of())).token(), "a JSON string claim prints with its quotes");
    }

    @Test
    void theRelayIsAServiceTokenOfThePrismApplicationAndNothingElse() {
        final McpResource r = resource(null);
        assertTrue(r.isRelay(jwt("s", Map.of("gluonify_kind", "service", "gluonify_app", "prism"))));
        assertTrue(r.isRelay(jwt("s", Map.of("gluonify_kind", List.of("service"), "gluonify_app", "\"prism\""))));
        assertFalse(r.isRelay(jwt("s", Map.of("gluonify_kind", "user", "gluonify_app", "prism"))));
        assertFalse(r.isRelay(jwt("s", Map.of("gluonify_kind", "service", "gluonify_app", "other"))));
        assertFalse(r.isRelay(jwt("s", Map.of("gluonify_kind", "service"))));
        assertFalse(r.isRelay(jwt("s", Map.of())));
        assertFalse(r.isRelay(jwt("s", Map.of("gluonify_kind", List.of("service", "user"), "gluonify_app", "prism"))));
        r.relayApp = "console";
        assertTrue(r.isRelay(jwt("s", Map.of("gluonify_kind", "service", "gluonify_app", "console"))));
    }

    @Test
    void theCallerCarriesTheSubjectAndTheRolesOfTheIdentityAndOfTheToken() {
        final JsonWebToken token = jwt("app:prism", RELAY);
        final McpResource r = resource(identity(false, token, Set.of("from-identity")));
        final McpCaller caller = r.caller(token);
        assertEquals("app:prism", caller.subject());
        assertEquals(Set.of("from-identity", "gluonify:mcp:write"), caller.roles());
        assertSame(token, caller.token().orElseThrow());
        final McpCaller noRoles = resource(identity(false, token, new HashSet<>())).caller(jwt("s", Map.of("roles", "single-role")));
        assertEquals(Set.of("single-role"), noRoles.roles());
        assertEquals(Set.of(), resource(identity(false, token, Set.of())).caller(jwt("s", Map.of())).roles());
    }

    // --- HTTP answers ---

    static String body(final Response r) {
        return (String) r.getEntity();
    }

    @Test
    void withoutAValidTokenTheAnswerIs401WithABearerChallenge() throws IOException {
        final Response r = resource(identity(true, null, Set.of())).post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(401, r.getStatus());
        assertEquals("Bearer", r.getHeaderString("WWW-Authenticate"));
    }

    @Test
    void aValidTokenThatIsNotTheRelayIs403() throws IOException {
        final JsonWebToken user = jwt("someone", Map.of("aud", "gluonify-shop", "gluonify_kind", "user"));
        final Response r = resource(identity(false, user, Set.of())).post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(403, r.getStatus());
        assertTrue(body(r).contains("only the platform's relay"), body(r));
    }

    @Test
    void theRelayListsAndCallsTools() throws IOException {
        final JsonWebToken relay = jwt("app:prism", RELAY);
        final McpResource r = resource(identity(false, relay, Set.of("extra")), new Tools());
        final Response list = r.post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), "not a number");
        assertEquals(200, list.getStatus());
        assertTrue(body(list).contains("who_am_i"), body(list));
        final String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"who_am_i\"}}";
        final Response called = r.post(new ByteArrayInputStream(call.getBytes(StandardCharsets.UTF_8)), Integer.toString(call.length()));
        assertTrue(body(called).contains("app:prism [extra, gluonify:mcp:write]"), body(called));
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws IOException {
        final McpResource r = resource(identity(false, jwt("app:prism", RELAY), Set.of()));
        final Response big = r.post(new ByteArrayInputStream(new byte[0]), Long.toString(McpContract.MAX_BODY_BYTES + 1L));
        assertEquals(413, big.getStatus());
        assertEquals(200, r.post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), Long.toString(McpContract.MAX_BODY_BYTES)).getStatus());
        assertEquals(200, r.post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), "").getStatus(), "an empty length is not a number");
        assertEquals(200, r.post(new ByteArrayInputStream(LIST.getBytes(StandardCharsets.UTF_8)), "-5").getStatus(), "a negative length is ignored");
    }

    @Test
    void aMissingBodyIsAnInvalidRequestAndANotificationHasNoContent() throws IOException {
        final McpResource r = resource(identity(false, jwt("app:prism", RELAY), Set.of()));
        final Response none = r.post(null, null);
        assertEquals(200, none.getStatus());
        assertTrue(body(none).contains("-32600"), body(none));
        final Response notification = r.post(new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(202, notification.getStatus());
        assertNull(notification.getEntity());
    }

    // --- startup ---

    @Test
    void theToolsAreReadOnceAtStartupThroughTheClientProxiesOfTheBeans() {
        final McpResource r = resource(identity(true, null, Set.of()), new Tools());
        r.toolboxes = instance(List.<McpToolbox>of(new ToolsProxyToolbox(new Tools())));
        r.onStart(null);
        final McpServer first = r.server();
        assertSame(first, r.server());
        assertEquals(1, first.registry().size(), "the tools of the bean behind the proxy");
    }

    /** A toolbox that is also a client proxy: the resource unwraps it before reading its methods. */
    static final class ToolsProxyToolbox extends Tools implements ClientProxy {
        final Object delegate;

        ToolsProxyToolbox(final Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object arc_contextualInstance() {
            return delegate;
        }

        @Override
        public InjectableBean<?> arc_bean() {
            return null;
        }
    }

    @Test
    void anObjectMapperOfTheApplicationIsUsedWhenThereIsOne() {
        final McpResource r = resource(identity(true, null, Set.of()), new Tools());
        r.mappers = instance(List.of(new ObjectMapper()));
        assertEquals(1, r.server().registry().size());
    }

    @Test
    void theProxyStandInDelegates() {
        final Tools tools = new Tools();
        assertSame(tools, ClientProxy.unwrap(new ToolsProxy(tools)));
        assertNull(new ToolsProxy(tools).arc_bean());
    }
}

package io.gluonify.plasma.quarkus.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.gluonify.plasma.mcp.McpContract;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.ClaimType;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

/** {@code POST /mcp} in a Quarkus application: identity (Charm token of the application's audience), write role, HTTP statuses. */
@QuarkusTest
class McpResourceTest {
    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    static final String RESET = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"reset_cache\"}}";
    static final String STATS = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"order_stats\",\"arguments\":{\"days\":2,\"tags\":[]}}}";

    static ValidatableResponse post(final String body) {
        return given().contentType("application/json").body(body).when().post(McpContract.PATH).then();
    }

    @Test
    void anonymousIs401WithABearerChallenge() {
        post(LIST).statusCode(401).header("WWW-Authenticate", "Bearer").body("error", equalTo("a valid application token is required"));
    }

    @Test
    @TestSecurity(user = "alice")
    void aPrincipalThatIsNotAJwtIs401() {
        post(LIST).statusCode(401);
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = @Claim(key = "aud", value = "gluonify-other"))
    void anotherAudienceIs401() {
        post(LIST).statusCode(401);
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "sub", value = "app:prism"), @Claim(key = "gluonify_kind", value = "service"), @Claim(key = "gluonify_app", value = "prism")})
    void prismServiceTokenListsAndCallsReadTools() {
        post(LIST).statusCode(200).body("result.tools.name", hasItem("order_stats")).body("result.tools.find { it.name == 'reset_cache' }.write", equalTo(true));
        post(STATS).statusCode(200).body("result.structuredContent.orders", equalTo(20)).body("result.isError", equalTo(false));
        post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}").statusCode(200)
                .body("result.protocolVersion", equalTo("2024-11-05")).body("result.serverInfo.version", equalTo("1.0.0"));
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "[\"gluonify-shop\",\"gluonify-x\"]", type = ClaimType.JSON_ARRAY), @Claim(key = "gluonify_kind", value = "service"), @Claim(key = "gluonify_app", value = "prism")})
    void writeToolWithoutTheWriteRoleIs403() {
        post(RESET).statusCode(403).body("error", equalTo("missing role: gluonify:mcp:write"));
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "sub", value = "app:prism"), @Claim(key = "gluonify_kind", value = "service"), @Claim(key = "gluonify_app", value = "prism"),
            @Claim(key = "roles", value = "[\"gluonify:mcp:write\"]", type = ClaimType.JSON_ARRAY)})
    void writeToolWithTheWriteRoleClaim() {
        post(RESET).statusCode(200).body("result.content.text", contains("cache emptied for app:prism"));
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "gluonify_kind", value = "service"), @Claim(key = "gluonify_app", value = "prism")})
    void notificationsBodySizeAndMethods() {
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode(202).body(emptyString());
        post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\""
                + "z".repeat(McpContract.MAX_BODY_BYTES) + "\"}}}").statusCode(413).body("error", equalTo("request too large"));
        post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").statusCode(200).body("error.code", equalTo(-32601));
        given().when().get(McpContract.PATH).then().statusCode(405);
    }

    static final String RELAY_ONLY = "only the platform's relay (Prism) may call /mcp";

    @Test
    @TestSecurity(user = "alice", roles = "gluonify:mcp:write")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "sub", value = "alice"), @Claim(key = "org", value = "acme")})
    void aUserTokenOfTheRightAudienceIs403() {
        post(LIST).statusCode(403).body("error", equalTo(RELAY_ONLY));
        post(RESET).statusCode(403).body("error", equalTo(RELAY_ONLY));
    }

    @Test
    @TestSecurity(user = "app:bup")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "sub", value = "app:bup"), @Claim(key = "gluonify_kind", value = "service"),
            @Claim(key = "gluonify_app", value = "bup")})
    void anotherApplicationsServiceTokenIs403() {
        post(LIST).statusCode(403).body("error", equalTo(RELAY_ONLY));
    }

    @Test
    @TestSecurity(user = "app:prism")
    @JwtSecurity(claims = {@Claim(key = "aud", value = "gluonify-shop"), @Claim(key = "gluonify_app", value = "prism")})
    void prismWithoutTheServiceKindIs403() {
        post(LIST).statusCode(403).body("error", equalTo(RELAY_ONLY));
    }
}

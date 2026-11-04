package io.gluonify.plasma.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One binary serves the console at the root and under the prefix the proxy strips: the base of the page is written when it is served, not at build time.
 *
 * @author David CABILLIC
 */
@QuarkusTest
class ConsoleIndexTest {

    private static final String BASE_ROOT = "<head><base href=\"/\">";

    private static final String BASE_FERMION = "<head><base href=\"/fermion/\">";

    @Test
    void withoutAProxyTheConsoleLivesAtTheRoot() {

        for (final String page : new String[] {"/", "/index.html"}) {
            given().when().get(page).then().statusCode(200).contentType(startsWith("text/html")).header("Cache-Control", containsString("no-cache"))
                    .body(containsString(BASE_ROOT)).body(containsString("<div id=\"app\">")).body(containsString("\"./assets/")); // the assets are relative: the base places them
        }
    }

    @Test
    void behindTheProxyTheBaseIsTheStrippedPrefix() {

        for (final String page : new String[] {"/", "/index.html"}) {
            given().header(ConsoleIndex.FORWARDED_PREFIX, "/fermion").when().get(page).then().statusCode(200).body(containsString(BASE_FERMION));
        }
        given().header(ConsoleIndex.FORWARDED_PREFIX, "/fermion/").when().get("/").then().statusCode(200).body(containsString(BASE_FERMION));
        given().header(ConsoleIndex.FORWARDED_PREFIX, "/tools/fermion").when().get("/").then().statusCode(200).body(containsString("<base href=\"/tools/fermion/\">"));
        given().header(ConsoleIndex.FORWARDED_PREFIX, "/fermion").when().head("/index.html").then().statusCode(200).contentType(startsWith("text/html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "/", "//", "fermion", "//evil.example/x", "https://evil.example/", "/a/../b", "/a/./b", "/x\"><script>", "/x y", "/%2e%2e/", "/a//b", "/...", "/a{b", "/a@b", "/a[b"})
    void aPrefixThatIsNotAPlainPathIsIgnored(final String hostile) {

        assertEquals("/", ConsoleIndex.base(hostile));
        given().header(ConsoleIndex.FORWARDED_PREFIX, hostile).when().get("/").then().statusCode(200).body(containsString(BASE_ROOT));
    }

    @Test
    void otherRequestsAreLeftAlone() {

        given().header(ConsoleIndex.FORWARDED_PREFIX, "/fermion").when().get("/assets/index-abc123.js").then().statusCode(404).body(not(containsString("<base href"))); // not the page
        given().when().post("/index.html").then().statusCode(not(200)); // only GET and HEAD serve the page
        assertEquals("/fermion/", ConsoleIndex.base(" /fermion "));
        assertEquals("/v1.2/", ConsoleIndex.base("/v1.2"));
        assertEquals("/a.b/c_d~e/", ConsoleIndex.base("/a.b/c_d~e///"));
    }
}

package io.gluonify.plasma.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gluonify.plasma.services.internal.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake cluster for tests (published in the test-jar, also used by the CLI): Higgs discovery document and a minimal Charm (OpenID
 * configuration, {@code /authorize} with PKCE S256, {@code /token}: single-use code and refresh, like Charm).
 */
public final class FakeCluster implements AutoCloseable {
    public static final String CLIENT_ID = "gluonify-cli";

    private final HttpServer server;
    public final AtomicInteger discoveryHits = new AtomicInteger();
    public final AtomicInteger refreshes = new AtomicInteger();
    public final AtomicInteger codeExchanges = new AtomicInteger();
    public volatile int discoveryStatus = 200;
    /** Lifetime of the issued access tokens (seconds). */
    public volatile long accessTtl = 3600;
    /** Audiences carried by the issued tokens. */
    public volatile List<String> audiences = List.of("gluonify-crystallon", "gluonify-fermion");
    /** Services published by discovery: name -> path on this server. */
    public final Map<String, String> services = new ConcurrentHashMap<>(Map.of("crystallon", "/crystallon", "fermion", "/fermion"));
    /** Last request received by /authorize (parameters). */
    public volatile Map<String, String> lastAuthorize;
    /** Last form received by /token. */
    public volatile Map<String, String> lastToken;

    private final Map<String, String[]> codes = new ConcurrentHashMap<>(); // maps an authorization code to its challenge and redirect URI
    private final Map<String, Boolean> refreshTokens = new ConcurrentHashMap<>(); // maps a refresh token to whether it was already used
    private final AtomicInteger seq = new AtomicInteger();

    public FakeCluster() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/.well-known/gluonify", this::discovery);
        server.createContext("/realms/gluonify/.well-known/openid-configuration", this::openid);
        server.createContext("/realms/gluonify/authorize", this::authorize);
        server.createContext("/realms/gluonify/token", this::token);
        server.createContext("/realms/gluonify/revoke", ex -> reply(ex, 200, "{}"));
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public URI discoveryUrl() {
        return URI.create(base() + "/.well-known/gluonify");
    }

    public String issuer() {
        return base() + "/realms/gluonify";
    }

    /** A valid (unused) refresh token issued by this fake Charm. */
    public String newRefreshToken() {
        final String rt = "rt-" + seq.incrementAndGet();
        refreshTokens.put(rt, Boolean.FALSE);
        return rt;
    }

    /** A fake JWT (dummy signature) with these audiences. */
    public String jwt(final List<String> aud) {
        final Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        final Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "u-1");
        claims.put("preferred_username", "ada");
        claims.put("aud", aud);
        claims.put("n", seq.incrementAndGet());
        return b.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "." + b.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private void discovery(final HttpExchange ex) throws IOException {
        discoveryHits.incrementAndGet();
        if (discoveryStatus != 200) {
            reply(ex, discoveryStatus, "{\"error\":\"nope\"}");
            return;
        }
        final Map<String, Object> svc = new LinkedHashMap<>();
        services.forEach((k, v) -> svc.put(k, Map.of("url", base() + v, "audience", "gluonify-" + k)));
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", "test.lan");
        m.put("environment", "SBX");
        m.put("issuer", issuer());
        m.put("cli", Map.of("clientId", CLIENT_ID));
        m.put("services", svc);
        reply(ex, 200, Json.write(m));
    }

    private void openid(final HttpExchange ex) throws IOException {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", issuer());
        m.put("authorization_endpoint", issuer() + "/authorize");
        m.put("token_endpoint", issuer() + "/token");
        m.put("revocation_endpoint", issuer() + "/revoke");
        m.put("code_challenge_methods_supported", List.of("S256"));
        reply(ex, 200, Json.write(m));
    }

    private void authorize(final HttpExchange ex) throws IOException {
        final Map<String, String> p = form(ex.getRequestURI().getRawQuery());
        lastAuthorize = p;
        final String redirect = p.get("redirect_uri");
        if (!CLIENT_ID.equals(p.get("client_id")) || redirect == null || !redirect.matches("http://127\\.0\\.0\\.1:\\d+/callback")
                || !"code".equals(p.get("response_type")) || !"S256".equals(p.get("code_challenge_method")) || p.get("code_challenge") == null) {
            reply(ex, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        final String code = "code-" + seq.incrementAndGet();
        codes.put(code, new String[] {p.get("code_challenge"), redirect});
        ex.getResponseHeaders().add("Location", redirect + "?code=" + code + "&state=" + p.get("state") + "&iss=" + java.net.URLEncoder.encode(issuer(), StandardCharsets.UTF_8));
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void token(final HttpExchange ex) throws IOException {
        final Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastToken = f;
        if (!CLIENT_ID.equals(f.get("client_id"))) {
            reply(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if ("authorization_code".equals(f.get("grant_type"))) {
            final String[] c = codes.remove(String.valueOf(f.get("code")));
            if (c == null || !c[1].equals(f.get("redirect_uri")) || f.get("code_verifier") == null || !c[0].equals(s256(f.get("code_verifier")))) {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            codeExchanges.incrementAndGet();
            reply(ex, 200, tokens());
            return;
        }
        if ("refresh_token".equals(f.get("grant_type"))) {
            final String rt = f.get("refresh_token");
            final Boolean used = rt == null ? null : refreshTokens.replace(rt, Boolean.TRUE);
            if (used == null || used) {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            refreshes.incrementAndGet();
            reply(ex, 200, tokens());
            return;
        }
        reply(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
    }

    private String tokens() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", jwt(audiences));
        m.put("token_type", "Bearer");
        m.put("expires_in", accessTtl);
        m.put("refresh_token", newRefreshToken());
        m.put("refresh_expires_in", 86400);
        return Json.write(m);
    }

    public static String s256(final String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> form(final String raw) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (final String kv : raw.split("&")) {
            final int eq = kv.indexOf('=');
            final String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            final String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void reply(final HttpExchange ex, final int status, final String body) throws IOException {
        final byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

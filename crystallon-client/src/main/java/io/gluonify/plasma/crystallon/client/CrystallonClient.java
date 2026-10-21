package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.Check;
import io.gluonify.plasma.crystallon.ErrorBody;
import io.gluonify.plasma.crystallon.InlineRenderRequest;
import io.gluonify.plasma.crystallon.RenderRequest;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Crystallon client for applications: renders to PDF (or validates) a template stored in Crystallon, designated by its identifier or its
 * name, or a template that the application sends with the request ({@link #inline(Object)}).
 *
 * <pre>{@code
 * CrystallonClient crystallon = CrystallonClient.create();                 // no configuration: platform, developer workstation or fake services
 * RenderedPdf pdf = crystallon.byName("Gluonify invoice").render(payload);
 * RenderedPdf quote = crystallon.inline("quote").render(payload);          // a preset, or a template object: nothing to store
 * }</pre>
 *
 * <p>Java 17, no mandatory dependency beyond the platform SDK: no CDI, no Quarkus, and the JSON library (Jackson 2 or 3) is the
 * application's ({@link PayloadCodec#detect()}). The client is immutable and thread-safe; it keeps an {@link HttpClient} created on the first call
 * (never in a static field: native image).
 */
public final class CrystallonClient {
    /** Environment variable injected by the platform when the application declares {@code crystallon} in {@code uses}. */
    static final String ENV_URL = "GLUONIFY_SERVICE_CRYSTALLON_URL";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final long DEFAULT_MAX_PDF_BYTES = 50L * 1024 * 1024;
    private static final String PAGES_HEADER = "X-Crystallon-Pages";
    private static final String WARNINGS_HEADER = "X-Crystallon-Warnings";
    private static final String PDFA_HEADER = "X-Crystallon-PDFA";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String base;
    private final CrystallonTokens tokens;
    private final Duration timeout;
    private final PayloadCodec json;
    private final long maxPdfBytes;
    private final HttpClient configuredHttp;
    private final Object httpLock = new Object();
    private final AtomicReference<HttpClient> http = new AtomicReference<>();

    private CrystallonClient(final Builder b) {
        this.base = b.baseUrl.toString().replaceAll("/+$", "");
        this.tokens = b.tokens;
        this.timeout = b.timeout;
        this.json = b.json != null ? b.json : PayloadCodec.detect();
        this.maxPdfBytes = b.maxPdfBytes;
        this.configuredHttp = b.httpClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A client with no configuration at all: the address and the token come from {@link GluonifyServices#endpoint(String)}, depending on where
     * the application runs. On the platform, the address injected for {@code "uses": ["crystallon"]} and the application token (file re-read on every
     * call); on the developer workstation, the public address of the {@code gluonify login} zone and the developer token (renewed); in
     * tests, the {@code GLUONIFY_SERVICE_CRYSTALLON_URL} override (system property, set by {@code GluonifyTestServices}). The JSON comes from
     * {@link PayloadCodec#detect()}.
     *
     * @throws io.gluonify.plasma.services.GluonifyServicesException if Crystallon cannot be reached; the message says what to do
     * @throws IllegalStateException if no JSON library is present
     */
    public static CrystallonClient create() {
        return create(GluonifyServices.defaults());
    }

    /** Like {@link #create()} with a supplied resolution (tests, tools). */
    public static CrystallonClient create(final GluonifyServices services) {
        return builder().endpoint(services.resolve("crystallon")).build();
    }

    /**
     * A client configured by the platform environment: the address comes from {@code GLUONIFY_SERVICE_CRYSTALLON_URL} (injected when
     * the application declares {@code crystallon} in {@code uses}), the token from {@link CrystallonTokens#fromEnvironment()}, the JSON from
     * {@link PayloadCodec#detect()}.
     *
     * @throws IllegalStateException if the address is absent, or if no JSON library is present
     */
    public static CrystallonClient fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static CrystallonClient fromEnvironment(final Function<String, String> environment) {
        final String url = environment.apply(ENV_URL);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(ENV_URL + " is not set: the platform did not grant the service \"crystallon\" (build the application with Bup with this module "
                    + "on its runtime classpath, or add \"crystallon\" to \"uses\" in gluonify.json), or build the client with CrystallonClient.builder().baseUrl(...)");
        }
        return builder().baseUrl(url.strip()).tokens(CrystallonTokens.fromEnvironment(environment)).build();
    }

    /** The template with identifier {@code id} (the one from the Crystallon editor). */
    public TemplateRef byId(final String id) {
        return new TemplateRef(this, "/v1/templates/" + segment(required(id, "id")), null);
    }

    /** The template with this name (spaces and special characters allowed: the name is encoded as a path segment). */
    public TemplateRef byName(final String name) {
        return new TemplateRef(this, "/v1/templates/by-name/" + segment(required(name, "name")), null);
    }

    /**
     * A template sent with every request, without storing anything in Crystallon ({@code POST /v1/render}): the name of a preset
     * ({@code invoice}, {@code letter}, {@code quote}) or the template itself (a JSON object, optionally with {@code extends}). The template is
     * serialized immediately by the {@link PayloadCodec}: the {@link InlineTemplate} keeps a copy, which later modification of the passed object does not
     * change, and a template that cannot be serialized fails here rather than at the first render. No network call.
     *
     * @param template a {@code String}: the name of a preset; otherwise an object that serializes to a JSON object ({@code Map}, record, bean,
     *                 {@code JsonNode} of the codec's Jackson). JSON written in a {@code String} is not accepted: read it first (into a {@code Map},
     *                 a {@code JsonNode})
     * @throws IllegalArgumentException if the template is {@code null}, an empty name, JSON in a string, or does not serialize to a JSON object
     */
    public InlineTemplate inline(final Object template) {
        return new InlineTemplate(this, snapshot(template));
    }

    private Object snapshot(final Object template) {
        if (template == null) {
            throw new IllegalArgumentException("the template must be the name of a predefined template or a template object, not null");
        }
        if (template instanceof String name) {
            final String preset = name.strip();
            if (preset.isEmpty()) {
                throw new IllegalArgumentException("the predefined template name must not be blank");
            }
            if (preset.startsWith("{") || preset.startsWith("[")) {
                throw new IllegalArgumentException("a String is the name of a predefined template; for a template written as JSON, pass a Map, a record or a "
                        + "JsonNode (read the JSON text first)");
            }
            return preset;
        }
        final byte[] bytes = json.write(template);
        final Map<?, ?> copy;
        try {
            copy = json.read(bytes, Map.class);
        }
        catch (final RuntimeException e) {
            throw new IllegalArgumentException("the template must serialize to a JSON object (a Map, a record, a bean or a JsonNode object) or be the name "
                    + "of a predefined template", e);
        }
        if (copy == null) {
            throw new IllegalArgumentException("the template must serialize to a JSON object, not null");
        }
        return copy; // private to the InlineTemplate: never handed out, only read
    }

    RenderedPdf render(final String templatePath, final Integer version, final Object data, final RenderOptions options) {
        return renderPdf(templatePath + "/render" + renderQuery(options, version), new RenderRequest(data, options.params()));
    }

    Check validate(final String templatePath, final Integer version, final Object data, final RenderOptions options) {
        return check(templatePath + "/render/validate" + (version == null ? "" : "?version=" + version), new RenderRequest(data, options.params()));
    }

    RenderedPdf renderInline(final Object template, final Object data, final RenderOptions options) {
        return renderPdf("/v1/render" + renderQuery(options, null), new InlineRenderRequest(template, data, options.params()));
    }

    Check validateInline(final Object template, final Object data, final RenderOptions options) {
        return check("/v1/render/validate", new InlineRenderRequest(template, data, options.params()));
    }

    private static String renderQuery(final RenderOptions options, final Integer version) {
        return "?pdfa=" + options.pdfa() + (version == null ? "" : "&version=" + version);
    }

    private RenderedPdf renderPdf(final String pathAndQuery, final Object request) {
        final HttpResponse<BoundedBody.Body> response = post(pathAndQuery, "application/pdf", request);
        final int status = response.statusCode();
        final BoundedBody.Body body = response.body();
        if (status / 100 != 2) {
            throw failure(status, body);
        }
        if (body.tooLarge()) {
            throw new CrystallonException(status, null, "The PDF is larger than the limit of " + maxPdfBytes + " bytes", null);
        }
        final byte[] pdf = body.bytes();
        if (pdf.length < 4 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            throw new CrystallonException(status, null, "Crystallon did not return a PDF", null);
        }
        return new RenderedPdf(pdf, intHeader(response, PAGES_HEADER), intHeader(response, WARNINGS_HEADER), pdfa(response));
    }

    private Check check(final String pathAndQuery, final Object request) {
        final HttpResponse<BoundedBody.Body> response = post(pathAndQuery, "application/json", request);
        final int status = response.statusCode();
        final BoundedBody.Body body = response.body();
        if (status / 100 != 2) {
            throw failure(status, body);
        }
        try {
            final Check check = body.tooLarge() ? null : json.read(body.bytes(), Check.class);
            if (check != null) {
                return check;
            }
        }
        catch (final RuntimeException e) {
            throw new CrystallonException(status, null, "The validation answer of Crystallon is not readable", null, e);
        }
        throw new CrystallonException(status, null, "The validation answer of Crystallon is not readable", null);
    }

    private HttpResponse<BoundedBody.Body> post(final String pathAndQuery, final String accept, final Object requestBody) {
        final byte[] payload = json.write(requestBody);
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        final Optional<String> token = tokens.token();
        if (token.isPresent() && !token.get().isBlank()) {
            try {
                request.header("Authorization", "Bearer " + token.get());
            }
            catch (final IllegalArgumentException e) {
                throw new CrystallonException(0, null, "The Crystallon token is not a valid HTTP header value", null);
            }
        }
        try {
            return http().send(request.build(), BoundedBody.handler(maxPdfBytes));
        }
        catch (final HttpTimeoutException e) {
            throw new CrystallonException(0, null, "Crystallon did not answer within " + timeout, null, e);
        }
        catch (final IOException e) {
            throw new CrystallonException(0, null, "Crystallon is unreachable (" + e.getClass().getSimpleName() + ")", null, e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrystallonException(0, null, "The call to Crystallon was interrupted", null, e);
        }
    }

    private CrystallonException failure(final int status, final BoundedBody.Body body) {
        ErrorBody error = null;
        if (!body.tooLarge() && looksLikeJson(body.bytes())) {
            try {
                error = json.read(body.bytes(), ErrorBody.class);
            }
            catch (final RuntimeException e) {
                // not an ErrorBody (a proxy page...): keep the status alone
            }
        }
        if (error != null && (error.code() != null || error.message() != null)) {
            return new CrystallonException(status, error.code(), error.message() != null ? error.message() : defaultMessage(status), error.path());
        }
        return new CrystallonException(status, null, defaultMessage(status), null);
    }

    private static String defaultMessage(final int status) {
        return switch (status) {
            case 401 -> "Crystallon refused the call (HTTP 401): the token is missing, expired or not accepted";
            case 403 -> "Crystallon forbids the call (HTTP 403): the token does not carry the role gluonify:crystallon:render";
            default -> "Crystallon answered HTTP " + status;
        };
    }

    private static boolean looksLikeJson(final byte[] bytes) {
        for (final byte b : bytes) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                continue;
            }
            return b == '{';
        }
        return false;
    }

    private static int intHeader(final HttpResponse<?> response, final String name) {
        return response.headers().firstValue(name).map(v -> {
            try {
                return Math.max(0, Integer.parseInt(v.strip()));
            }
            catch (final NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
    }

    private static Boolean pdfa(final HttpResponse<?> response) {
        final String value = response.headers().firstValue(PDFA_HEADER).map(String::strip).orElse("");
        if (value.equalsIgnoreCase("valid") || value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("invalid") || value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private HttpClient http() {
        HttpClient h = http.get();
        if (h == null) {
            synchronized (httpLock) {
                h = http.get();
                if (h == null) {
                    final Duration connect = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
                    h = configuredHttp != null ? configuredHttp : HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build();
                    http.set(h);
                }
            }
        }
        return h;
    }

    private static String required(final String value, final String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the template " + what + " must not be blank");
        }
        return value;
    }

    /** Encodes a value as a path segment: everything except {@code A-Z a-z 0-9 - . _ ~} as {@code %XX} (UTF-8); space {@code %20}, slash {@code %2F}. */
    static String segment(final String value) {
        if (value.equals(".") || value.equals("..")) {
            return value.replace(".", "%2E");
        }
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final StringBuilder out = new StringBuilder(bytes.length + 8);
        for (final byte b : bytes) {
            final int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            }
            else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return out.toString();
    }

    public static final class Builder {
        private URI baseUrl;
        private CrystallonTokens tokens = CrystallonTokens.none();
        private Duration timeout = DEFAULT_TIMEOUT;
        private PayloadCodec json;
        private HttpClient httpClient;
        private long maxPdfBytes = DEFAULT_MAX_PDF_BYTES;

        private Builder() {
        }

        /** @param url Crystallon address ({@code http://} or {@code https://}), with an optional path prefix; the trailing slash is ignored */
        public Builder baseUrl(final String url) {
            return baseUrl(URI.create(Objects.requireNonNull(url, "url").strip()));
        }

        public Builder baseUrl(final URI url) {
            Objects.requireNonNull(url, "url");
            final String scheme = url.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || url.getHost() == null) {
                throw new IllegalArgumentException("the Crystallon URL must be an absolute http(s) URL");
            }
            if (url.getRawQuery() != null || url.getRawFragment() != null) {
                throw new IllegalArgumentException("the Crystallon URL must not have a query or a fragment");
            }
            this.baseUrl = url;
            return this;
        }

        /**
         * The address and token source of a service resolved by {@link GluonifyServices} ({@code GluonifyServices.endpoint("crystallon")}).
         * A later call to {@link #baseUrl(String)} or {@link #tokens(CrystallonTokens)} replaces the corresponding value.
         */
        public Builder endpoint(final Endpoint endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            baseUrl(endpoint.url());
            return tokens(CrystallonTokens.of(endpoint));
        }

        /** @param tokens the token source (default: {@link CrystallonTokens#none()}) */
        public Builder tokens(final CrystallonTokens tokens) {
            this.tokens = Objects.requireNonNull(tokens, "tokens");
            return this;
        }

        /** @param timeout timeout of a call, complete response included (default: 30 s) */
        public Builder timeout(final Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("the timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** @param codec the JSON gateway (default: {@link PayloadCodec#detect()}) */
        public Builder json(final PayloadCodec codec) {
            this.json = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** @param client the HTTP client to use (default: a client created on the first call) */
        public Builder httpClient(final HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        /** @param max maximum size of an accepted PDF, in bytes (default: 50 MB) */
        public Builder maxPdfBytes(final long max) {
            if (max < 1) {
                throw new IllegalArgumentException("the maximum PDF size must be positive");
            }
            this.maxPdfBytes = max;
            return this;
        }

        /** @throws IllegalStateException if the address is missing, or if no JSON library is present and no codec is given */
        public CrystallonClient build() {
            if (baseUrl == null) {
                throw new IllegalStateException("the Crystallon base URL is required: baseUrl(...), endpoint(...) or CrystallonClient.create()");
            }
            return new CrystallonClient(this);
        }
    }
}

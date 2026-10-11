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
import java.util.function.Function;

/**
 * Client de gluonify-crystallon pour les applications : rend en PDF (ou valide) un modèle stocké dans Crystallon, désigné par son
 * identifiant ou par son nom, ou un modèle que l'application envoie avec la demande ({@link #inline(Object)}).
 *
 * <pre>{@code
 * CrystallonClient crystallon = CrystallonClient.create();                 // aucune configuration : plateforme, poste du développeur ou faux services
 * RenderedPdf pdf = crystallon.byName("Gluonify invoice").render(payload);
 * RenderedPdf quote = crystallon.inline("quote").render(payload);          // modèle prédéfini, ou un objet modèle : rien à stocker
 * }</pre>
 *
 * <p>Java 17, aucune dépendance obligatoire hors du SDK de la plateforme : ni CDI, ni Quarkus, et la bibliothèque JSON (Jackson 2 ou 3) est celle de
 * l'application ({@link PayloadCodec#detect()}). Le client est immuable et sûr entre fils ; il garde un {@link HttpClient} créé au premier appel
 * (jamais dans un champ statique : image native).
 */
public final class CrystallonClient {
    /** Variable d'environnement injectée par la plateforme quand l'application déclare {@code crystallon} dans {@code uses}. */
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
    private volatile HttpClient http;

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
     * Un client sans aucune configuration : l'adresse et le jeton viennent de {@link GluonifyServices#endpoint(String)}, selon l'endroit où tourne
     * l'application. Sur la plateforme, l'adresse injectée pour {@code "uses": ["crystallon"]} et le jeton d'application (fichier relu à chaque
     * appel) ; sur le poste du développeur, l'adresse publique de la zone de {@code gluonify login} et le jeton du développeur (renouvelé) ; en
     * test, la surcharge {@code GLUONIFY_SERVICE_CRYSTALLON_URL} (propriété système, posée par {@code GluonifyTestServices}). Le JSON vient de
     * {@link PayloadCodec#detect()}.
     *
     * @throws io.gluonify.plasma.services.GluonifyServicesException si Crystallon ne peut pas être joint ; le message dit quoi faire
     * @throws IllegalStateException si aucune bibliothèque JSON n'est présente
     */
    public static CrystallonClient create() {
        return create(GluonifyServices.defaults());
    }

    /** Comme {@link #create()} avec une résolution fournie (tests, outils). */
    public static CrystallonClient create(final GluonifyServices services) {
        return builder().endpoint(services.resolve("crystallon")).build();
    }

    /**
     * Un client configuré par l'environnement de la plateforme : l'adresse vient de {@code GLUONIFY_SERVICE_CRYSTALLON_URL} (injectée quand
     * l'application déclare {@code crystallon} dans {@code uses}), le jeton de {@link CrystallonTokens#fromEnvironment()}, le JSON de
     * {@link PayloadCodec#detect()}.
     *
     * @throws IllegalStateException si l'adresse est absente, ou si aucune bibliothèque JSON n'est présente
     */
    public static CrystallonClient fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static CrystallonClient fromEnvironment(final Function<String, String> environment) {
        final String url = environment.apply(ENV_URL);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(ENV_URL + " is not set: declare crystallon in the application's uses (appspec), or build the client with "
                    + "CrystallonClient.builder().baseUrl(...)");
        }
        return builder().baseUrl(url.strip()).tokens(CrystallonTokens.fromEnvironment(environment)).build();
    }

    /** Le modèle d'identifiant {@code id} (celui de l'éditeur de Crystallon). */
    public TemplateRef byId(final String id) {
        return new TemplateRef(this, "/v1/templates/" + segment(required(id, "id")), null);
    }

    /** Le modèle de ce nom (espaces et caractères spéciaux admis : le nom est encodé comme segment de chemin). */
    public TemplateRef byName(final String name) {
        return new TemplateRef(this, "/v1/templates/by-name/" + segment(required(name, "name")), null);
    }

    /**
     * Un modèle envoyé avec chaque demande, sans rien stocker dans Crystallon ({@code POST /v1/render}) : le nom d'un modèle prédéfini
     * ({@code invoice}, {@code letter}, {@code quote}) ou le modèle lui-même (un objet JSON, avec éventuellement {@code extends}). Le modèle est
     * sérialisé tout de suite par le {@link PayloadCodec} : l'{@link InlineTemplate} garde une copie, que la modification ultérieure de l'objet passé ne
     * change pas, et un modèle qui n'est pas sérialisable échoue ici plutôt qu'au premier rendu. Aucun appel réseau.
     *
     * @param template une {@code String} : le nom d'un modèle prédéfini ; sinon un objet qui se sérialise en objet JSON ({@code Map}, record, bean,
     *                 {@code JsonNode} du Jackson du codec). Du JSON écrit dans une {@code String} n'est pas admis : le lire d'abord (une {@code Map},
     *                 un {@code JsonNode})
     * @throws IllegalArgumentException si le modèle est {@code null}, un nom vide, du JSON dans une chaîne, ou ne se sérialise pas en objet JSON
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
                // pas un ErrorBody (page d'un proxy...) : on garde le statut seul
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
        HttpClient h = http;
        if (h == null) {
            synchronized (httpLock) {
                h = http;
                if (h == null) {
                    h = configuredHttp != null ? configuredHttp : HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10)).build();
                    http = h;
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

    /** Encode une valeur comme un segment de chemin : tout sauf {@code A-Z a-z 0-9 - . _ ~} en {@code %XX} (UTF-8) ; espace {@code %20}, barre {@code %2F}. */
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

        /** @param url adresse de Crystallon ({@code http://} ou {@code https://}), avec un éventuel préfixe de chemin ; la barre finale est ignorée */
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
         * L'adresse et la source du jeton d'un service résolu par {@link GluonifyServices} ({@code GluonifyServices.endpoint("crystallon")}).
         * Un appel suivant à {@link #baseUrl(String)} ou {@link #tokens(CrystallonTokens)} remplace la valeur correspondante.
         */
        public Builder endpoint(final Endpoint endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            baseUrl(endpoint.url());
            return tokens(CrystallonTokens.of(endpoint));
        }

        /** @param tokens la source du jeton (défaut : {@link CrystallonTokens#none()}) */
        public Builder tokens(final CrystallonTokens tokens) {
            this.tokens = Objects.requireNonNull(tokens, "tokens");
            return this;
        }

        /** @param timeout délai d'un appel, réponse complète comprise (défaut : 30 s) */
        public Builder timeout(final Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("the timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** @param codec la passerelle JSON (défaut : {@link PayloadCodec#detect()}) */
        public Builder json(final PayloadCodec codec) {
            this.json = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** @param client le client HTTP à utiliser (défaut : un client créé au premier appel) */
        public Builder httpClient(final HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        /** @param max taille maximale d'un PDF accepté, en octets (défaut : 50 Mo) */
        public Builder maxPdfBytes(final long max) {
            if (max < 1) {
                throw new IllegalArgumentException("the maximum PDF size must be positive");
            }
            this.maxPdfBytes = max;
            return this;
        }

        /** @throws IllegalStateException si l'adresse manque, ou si aucune bibliothèque JSON n'est présente et qu'aucun codec n'est donné */
        public CrystallonClient build() {
            if (baseUrl == null) {
                throw new IllegalStateException("the Crystallon base URL is required: baseUrl(...), endpoint(...) or CrystallonClient.create()");
            }
            return new CrystallonClient(this);
        }
    }
}

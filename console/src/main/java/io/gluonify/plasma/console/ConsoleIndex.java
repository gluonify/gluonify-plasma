package io.gluonify.plasma.console;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Serves the console's {@code index.html} with a {@code <base href>} that tells the browser where the console lives: the prefix the Gluonify proxy (Higgs) strips before calling
 * the application ({@code X-Forwarded-Prefix}, for example {@code /fermion}), the root without one. A console built with relative assets (Vite {@code base: './'}) is then served
 * by one binary at the root and under any prefix (decision of 2026-10-27: one binary per version); the kit's {@code appBase()} reads the base back. The header is read as it
 * comes: a value that is not a plain path (another origin, a dot segment, a character that would end the attribute) is taken as absent. Nothing to write in the application:
 * the bean is discovered with the jar. The page is the one Quinoa packages; when it is not packaged (dev mode, Vite serving the sources), every request goes through.
 *
 * @author David CABILLIC
 */
@ApplicationScoped
public class ConsoleIndex {

    /** Header set by the proxy that strips a prefix: the prefix, without a trailing slash. */
    public static final String FORWARDED_PREFIX = "X-Forwarded-Prefix";

    /** Where Quinoa packages the built console (a static resource of the application). */
    static final String INDEX = "META-INF/resources/index.html";

    /** Order of the filter among the application's: in front of the static resources, after the security filters. */
    static final int PRIORITY = 100;

    private static final Logger LOG = Logger.getLogger(ConsoleIndex.class);

    private static final String HEAD = "<head>";

    private static final String ROOT = "/";

    private static final char SLASH = '/';

    /** The packaged page split after its {@code <head>}; null when the page is not packaged. */
    private final Page page;

    /** The bean: reads the packaged page once. */
    public ConsoleIndex() {

        this(load());
    }

    /**
     * A filter for a given page.
     *
     * @param page the page to serve, null for none (every request goes through)
     */
    ConsoleIndex(final Page page) {

        this.page = page;
    }

    /**
     * The page, split where the base goes.
     *
     * @param head the page up to and including {@code <head>}
     * @param tail the rest of the page
     */
    record Page(String head, String tail) {

        /**
         * The page with its base.
         *
         * @param base the base of the console, with its trailing slash
         * @return the HTML to serve
         */
        String with(final String base) {

            return head + "<base href=\"" + base + "\">" + tail;
        }
    }

    /**
     * Registers the filter in front of the static resources.
     *
     * @param filters the HTTP filters of the application
     */
    void install(@Observes final Filters filters) {

        filters.register(this::serve, PRIORITY);
    }

    /**
     * Serves {@code index.html} with the base of the request, and lets every other request through.
     *
     * @param ctx the request
     */
    void serve(final RoutingContext ctx) {

        if (page == null || !isIndex(ctx)) {
            ctx.next();
            return;
        }
        ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8").putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .end(page.with(base(ctx.request().getHeader(FORWARDED_PREFIX))));
    }

    /** A GET or HEAD of the page itself (the SPA fallback reroutes the console's routes to it). */
    private static boolean isIndex(final RoutingContext ctx) {

        final HttpMethod method = ctx.request().method();
        final String path = ctx.normalizedPath();
        return (method == HttpMethod.GET || method == HttpMethod.HEAD) && (ROOT.equals(path) || "/index.html".equals(path));
    }

    /**
     * The base of the console for a forwarded prefix.
     *
     * @param forwardedPrefix the {@code X-Forwarded-Prefix} header, or null
     * @return the prefix with a trailing slash, {@code /} without a usable one
     */
    public static String base(final String forwardedPrefix) {

        if (forwardedPrefix == null) return ROOT;
        final String prefix = trimTrailingSlashes(forwardedPrefix.strip());
        return isPlainPath(prefix) ? prefix + ROOT : ROOT;
    }

    private static String trimTrailingSlashes(final String s) {

        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == SLASH) end--;
        return s.substring(0, end);
    }

    /** Segments of unreserved characters only ({@code /fermion}, {@code /a/b.c}): no scheme, no host, no empty or dot segment, nothing that ends an HTML attribute. */
    private static boolean isPlainPath(final String path) {

        if (path.isEmpty() || path.charAt(0) != SLASH) return false;
        int segmentStart = 1;
        for (int i = 1; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == SLASH) {
                if (!isPlainSegment(path, segmentStart, i)) return false;
                segmentStart = i + 1;
            } else if (!isUnreserved(path.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** A segment is non-empty and is not made of dots only. */
    private static boolean isPlainSegment(final String path, final int from, final int to) {

        if (from >= to) return false;
        for (int i = from; i < to; i++) {
            if (path.charAt(i) != '.') return true;
        }
        return false;
    }

    private static boolean isUnreserved(final char c) {

        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /** Reads the packaged index.html and splits it after its {@code <head>}: null when it is not packaged or has no head. */
    static Page load() {

        return load(() -> Thread.currentThread().getContextClassLoader().getResourceAsStream(INDEX));
    }

    /**
     * Reads a page and splits it after its {@code <head>}.
     *
     * @param source where the page comes from; null for none
     * @return the page split, or null when there is none, it cannot be read or it has no head (the base is then left to the browser)
     */
    static Page load(final Supplier<InputStream> source) {

        try (InputStream in = source.get()) {
            return in == null ? null : split(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException e) {
            LOG.warnf(e, "%s cannot be read: the console base is left to the browser", INDEX);
            return null;
        }
    }

    /**
     * Splits a page after its {@code <head>}.
     *
     * @param html the page
     * @return the page split, or null when it has no head (the base is then left to the browser)
     */
    static Page split(final String html) {

        final int head = html.indexOf(HEAD);
        if (head < 0) {
            LOG.warnf("%s has no <head>: the console base is left to the browser", INDEX);
            return null;
        }
        final int at = head + HEAD.length();
        return new Page(html.substring(0, at), html.substring(at));
    }
}

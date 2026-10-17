package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.Check;
import java.util.Map;

/**
 * A template that the application sends with every render request (see {@link CrystallonClient#inline(Object)}), instead of designating a template
 * stored in Crystallon: the name of a preset ({@code invoice}, {@code letter}, {@code quote}) or the template itself, a JSON object.
 * The routes are {@code POST /v1/render?pdfa=…} and {@code POST /v1/render/validate}; the body is {@code {template, data, params}}.
 * Same options, same errors, same limits as for a stored template ({@link TemplateRef}).
 *
 * <p>Immutable and stateless: it can be kept and used from several threads. The template is frozen at creation (private copy: modifying
 * the passed object afterwards changes nothing) and sent on every call; there is no notion of version, nothing is stored. The template may carry default
 * {@code params}; those of the request win, then those of the template, then those of the server.
 *
 * <pre>{@code
 * InlineTemplate quote = crystallon.inline("quote");                                          // a preset
 * InlineTemplate own = crystallon.inline(Map.of("extends", "invoice", "theme", Map.of("primary", "#0b5cad")));  // the application owns it
 * RenderedPdf pdf = own.render(payload);
 * }</pre>
 */
public final class InlineTemplate {
    private final CrystallonClient client;
    /** The name of a preset ({@code String}) or a private copy of the template ({@code Map}). */
    private final Object template;

    InlineTemplate(final CrystallonClient client, final Object template) {
        this.client = client;
        this.template = template;
    }

    /** Renders the document as PDF/A with the template's parameters, then the server's. @param data the payload (record, bean or {@code Map}); {@code null} gives {@code {}} */
    public RenderedPdf render(final Object data) {
        return render(data, RenderOptions.DEFAULT);
    }

    /** Renders the document. @param data the payload; {@code null} gives {@code {}} @param options {@code null} gives {@link RenderOptions#DEFAULT} @throws CrystallonException if Crystallon refuses or does not answer */
    public RenderedPdf render(final Object data, final RenderOptions options) {
        return client.renderInline(template, data, options == null ? RenderOptions.DEFAULT : options);
    }

    /** Validates the template with this payload without producing a PDF: the details of errors and warnings. */
    public Check validate(final Object data) {
        return validate(data, RenderOptions.DEFAULT);
    }

    /** Validates the template with this payload. @param options {@code null} gives {@link RenderOptions#DEFAULT} (only its parameters count) */
    public Check validate(final Object data, final RenderOptions options) {
        return client.validateInline(template, data, options == null ? RenderOptions.DEFAULT : options);
    }

    @Override
    public String toString() {
        return template instanceof Map<?, ?> keys ? "InlineTemplate[object, " + keys.size() + " keys]" : "InlineTemplate[preset " + template + "]";
    }
}

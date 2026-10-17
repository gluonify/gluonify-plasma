package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.Check;

/**
 * A Crystallon template, designated by its identifier ({@link CrystallonClient#byId}) or by its name ({@link CrystallonClient#byName}).
 * Immutable and stateless: it can be kept and used from several threads.
 *
 * <p>Without {@link #version(int)}, Crystallon renders the current version of the template (a change to the template in the editor is therefore picked up
 * immediately). With it, it renders that specific version, taken from the template's history.
 */
public final class TemplateRef {
    private final CrystallonClient client;
    private final String path;
    private final Integer version;

    TemplateRef(final CrystallonClient client, final String path, final Integer version) {
        this.client = client;
        this.path = path;
        this.version = version;
    }

    /** @return a reference that renders this specific version of the template ({@code ?version=n}) @throws IllegalArgumentException if {@code version} is less than 1 */
    public TemplateRef version(final int version) {
        if (version < 1) {
            throw new IllegalArgumentException("the template version must be at least 1");
        }
        return new TemplateRef(client, path, version);
    }

    /** Renders the document as PDF/A with the template's parameters, then the server's. @param data the payload (record, bean or {@code Map}); {@code null} gives {@code {}} */
    public RenderedPdf render(final Object data) {
        return render(data, RenderOptions.DEFAULT);
    }

    /** Renders the document. @param data the payload; {@code null} gives {@code {}} @param options {@code null} gives {@link RenderOptions#DEFAULT} @throws CrystallonException if Crystallon refuses or does not answer */
    public RenderedPdf render(final Object data, final RenderOptions options) {
        return client.render(path, version, data, options == null ? RenderOptions.DEFAULT : options);
    }

    /** Validates the template with this payload without producing a PDF: the details of errors and warnings. */
    public Check validate(final Object data) {
        return validate(data, RenderOptions.DEFAULT);
    }

    /** Validates the template with this payload. @param options {@code null} gives {@link RenderOptions#DEFAULT} (only its parameters count) */
    public Check validate(final Object data, final RenderOptions options) {
        return client.validate(path, version, data, options == null ? RenderOptions.DEFAULT : options);
    }

    @Override
    public String toString() {
        return "TemplateRef[" + path + (version == null ? "" : ", version=" + version) + "]";
    }
}

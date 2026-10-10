package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.Check;
import java.util.Map;

/**
 * Un modèle que l'application envoie avec chaque demande de rendu (voir {@link CrystallonClient#inline(Object)}), au lieu de désigner un modèle
 * stocké dans Crystallon : le nom d'un modèle prédéfini ({@code invoice}, {@code letter}, {@code quote}) ou le modèle lui-même, un objet JSON.
 * Les routes sont {@code POST /v1/render?pdfa=…} et {@code POST /v1/render/validate} ; le corps est {@code {template, data, params}}.
 * Mêmes options, mêmes erreurs, mêmes bornes que pour un modèle stocké ({@link TemplateRef}).
 *
 * <p>Immuable et sans état : on peut le garder et l'utiliser depuis plusieurs fils. Le modèle y est figé à la création (copie privée : modifier
 * l'objet passé ensuite ne change rien) et renvoyé à chaque appel ; il n'y a pas de notion de version, rien n'est stocké. Le modèle peut porter des
 * {@code params} par défaut ; ceux de la demande l'emportent, puis ceux du modèle, puis ceux du serveur.
 *
 * <pre>{@code
 * InlineTemplate quote = crystallon.inline("quote");                                          // modèle prédéfini
 * InlineTemplate own = crystallon.inline(Map.of("extends", "invoice", "theme", Map.of("primary", "#0b5cad")));  // l'application le possède
 * RenderedPdf pdf = own.render(payload);
 * }</pre>
 */
public final class InlineTemplate {
    private final CrystallonClient client;
    /** Le nom d'un modèle prédéfini ({@code String}) ou une copie privée du modèle ({@code Map}). */
    private final Object template;

    InlineTemplate(final CrystallonClient client, final Object template) {
        this.client = client;
        this.template = template;
    }

    /** Rend le document en PDF/A avec les paramètres du modèle, puis ceux du serveur. @param data le payload (record, bean ou {@code Map}) ; {@code null} donne {@code {}} */
    public RenderedPdf render(final Object data) {
        return render(data, RenderOptions.DEFAULT);
    }

    /** Rend le document. @param data le payload ; {@code null} donne {@code {}} @param options {@code null} donne {@link RenderOptions#DEFAULT} @throws CrystallonException si Crystallon refuse ou ne répond pas */
    public RenderedPdf render(final Object data, final RenderOptions options) {
        return client.renderInline(template, data, options == null ? RenderOptions.DEFAULT : options);
    }

    /** Valide le modèle avec ce payload sans produire de PDF : le détail des erreurs et des avertissements. */
    public Check validate(final Object data) {
        return validate(data, RenderOptions.DEFAULT);
    }

    /** Valide le modèle avec ce payload. @param options {@code null} donne {@link RenderOptions#DEFAULT} (seuls ses paramètres comptent) */
    public Check validate(final Object data, final RenderOptions options) {
        return client.validateInline(template, data, options == null ? RenderOptions.DEFAULT : options);
    }

    @Override
    public String toString() {
        return template instanceof Map<?, ?> keys ? "InlineTemplate[object, " + keys.size() + " keys]" : "InlineTemplate[preset " + template + "]";
    }
}

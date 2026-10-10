package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.Check;

/**
 * Un modèle de Crystallon, désigné par son identifiant ({@link CrystallonClient#byId}) ou par son nom ({@link CrystallonClient#byName}).
 * Immuable et sans état : on peut le garder et l'utiliser depuis plusieurs fils.
 *
 * <p>Sans {@link #version(int)}, Crystallon rend la version courante du modèle (un changement du modèle dans l'éditeur est donc pris en compte
 * tout de suite). Avec, il rend cette version précise, prise dans l'historique du modèle.
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

    /** @return une référence qui rend cette version précise du modèle ({@code ?version=n}) @throws IllegalArgumentException si {@code version} est inférieure à 1 */
    public TemplateRef version(final int version) {
        if (version < 1) {
            throw new IllegalArgumentException("the template version must be at least 1");
        }
        return new TemplateRef(client, path, version);
    }

    /** Rend le document en PDF/A avec les paramètres du modèle, puis ceux du serveur. @param data le payload (record, bean ou {@code Map}) ; {@code null} donne {@code {}} */
    public RenderedPdf render(final Object data) {
        return render(data, RenderOptions.DEFAULT);
    }

    /** Rend le document. @param data le payload ; {@code null} donne {@code {}} @param options {@code null} donne {@link RenderOptions#DEFAULT} @throws CrystallonException si Crystallon refuse ou ne répond pas */
    public RenderedPdf render(final Object data, final RenderOptions options) {
        return client.render(path, version, data, options == null ? RenderOptions.DEFAULT : options);
    }

    /** Valide le modèle avec ce payload sans produire de PDF : le détail des erreurs et des avertissements. */
    public Check validate(final Object data) {
        return validate(data, RenderOptions.DEFAULT);
    }

    /** Valide le modèle avec ce payload. @param options {@code null} donne {@link RenderOptions#DEFAULT} (seuls ses paramètres comptent) */
    public Check validate(final Object data, final RenderOptions options) {
        return client.validate(path, version, data, options == null ? RenderOptions.DEFAULT : options);
    }

    @Override
    public String toString() {
        return "TemplateRef[" + path + (version == null ? "" : ", version=" + version) + "]";
    }
}

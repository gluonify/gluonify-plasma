package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/**
 * Corps d'une demande de rendu ou de validation d'un modèle envoyé avec la demande ({@code POST /v1/render}, {@code POST /v1/render/validate}).
 * Comme {@link RenderRequest}, mais le modèle fait partie du corps au lieu d'être stocké dans Crystallon. {@code data} n'est jamais
 * {@code null} : sans données, c'est l'objet vide {@code {}} (un corps sans {@code data} ferait rendre l'exemple du modèle par le serveur).
 * {@code params} {@code null} est omis.
 *
 * @param template le nom d'un modèle prédéfini (une chaîne : {@code invoice}, {@code letter}, {@code quote}), ou le modèle lui-même (un objet JSON
 *                 une fois sérialisé : {@link Map}, record, bean), éventuellement avec {@code extends} ; jamais {@code null}
 * @param data     le payload du document (un objet JSON une fois sérialisé : record, {@link Map}, bean)
 * @param params   les paramètres de génération, ou {@code null} pour ceux du modèle puis du serveur
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InlineRenderRequest(Object template, Object data, RenderParams params) {

    /** @throws NullPointerException si {@code template} est {@code null} */
    public InlineRenderRequest {
        Objects.requireNonNull(template, "template");
        if (data == null) {
            data = Map.of();
        }
    }
}

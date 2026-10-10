package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Corps d'une demande de rendu ou de validation Crystallon. {@code data} n'est jamais {@code null} : sans données, c'est l'objet vide
 * {@code {}} (un corps sans {@code data} ferait rendre l'exemple du modèle par le serveur). {@code params} {@code null} est omis.
 *
 * @param data   le payload du document (un objet JSON une fois sérialisé : record, {@link Map}, bean)
 * @param params les paramètres de génération, ou {@code null} pour ceux du serveur
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenderRequest(Object data, RenderParams params) {

    public RenderRequest {
        if (data == null) {
            data = Map.of();
        }
    }
}

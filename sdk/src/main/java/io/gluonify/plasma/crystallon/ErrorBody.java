package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Corps d'erreur de tous les points d'accès de Crystallon.
 *
 * @param code    code stable, par exemple {@code CRYSTALLON-0005}
 * @param message message en anglais, sans risque à afficher
 * @param path    pour les problèmes de modèle : endroit dans le JSON du modèle ; absent sinon
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorBody(String code, String message, String path) {
}

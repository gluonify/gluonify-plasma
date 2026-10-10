package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Une erreur ou un avertissement de la validation d'un modèle.
 *
 * @param code    {@code CRYSTALLON-00NN} pour une erreur, {@code null} pour un avertissement
 * @param message message en anglais
 * @param path    endroit du problème dans le JSON du modèle (noms de clés et {@code [index]}, par exemple {@code body[3].blocks[0].text}),
 *                {@code null} quand le problème concerne tout le modèle
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Issue(String code, String message, String path) {
}

package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Paramètres de génération d'un document Crystallon (hors du payload) : ils pilotent les formats des nombres, des montants, des dates et des
 * adresses. Un champ {@code null} prend la valeur par défaut du serveur et n'est pas sérialisé.
 *
 * @param lang        {@code en}, {@code fr}, {@code es}, {@code it} ou {@code de}
 * @param country     code pays ISO 3166 alpha-2 (par exemple {@code CH})
 * @param currency    code devise ISO 4217 (par exemple {@code CHF})
 * @param timezone    fuseau IANA appliqué aux instants (par exemple {@code Europe/Zurich})
 * @param dateStyle   {@code short}, {@code medium}, {@code long}, {@code full} ou {@code iso}
 * @param numberStyle {@code locale}, {@code swiss} ou {@code plain}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenderParams(String lang, String country, String currency, String timezone, String dateStyle, String numberStyle) {
}

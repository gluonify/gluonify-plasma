package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Generation parameters of a Crystallon document (outside the payload): they drive the formats of numbers, amounts, dates and
 * addresses. A {@code null} field is not serialized: the parameter takes the default value of the template if it carries one ({@code params} at the
 * root of the template), otherwise that of the server; a field that is set always wins.
 *
 * @param lang        {@code en}, {@code fr}, {@code es}, {@code it} or {@code de}
 * @param country     ISO 3166 alpha-2 country code (for example {@code CH})
 * @param currency    ISO 4217 currency code (for example {@code CHF})
 * @param timezone    IANA time zone applied to instants (for example {@code Europe/Zurich})
 * @param dateStyle   {@code short}, {@code medium}, {@code long}, {@code full} or {@code iso}
 * @param numberStyle {@code locale}, {@code swiss} or {@code plain}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenderParams(String lang, String country, String currency, String timezone, String dateStyle, String numberStyle) {
}

package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.RenderParams;

/**
 * Options d'un rendu : les paramètres de génération ({@link RenderParams}) et la conversion PDF/A-1B. Immuable. {@link #DEFAULT} : PDF/A, et
 * aucun paramètre demandé : chacun prend la valeur du modèle s'il en porte une ({@code params} à la racine du modèle), sinon le défaut du serveur
 * (la langue du document, le pays, la devise... se déduisent de {@code lang}, anglais par défaut). Un paramètre renseigné ici l'emporte toujours.
 */
public final class RenderOptions {

    /** PDF/A-1B, aucun paramètre demandé (ceux du modèle, puis du serveur). */
    public static final RenderOptions DEFAULT = builder().build();

    private final RenderParams params;
    private final boolean pdfa;

    private RenderOptions(final RenderParams params, final boolean pdfa) {
        this.params = params;
        this.pdfa = pdfa;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return les paramètres de génération ; jamais {@code null} (les champs absents valent {@code null}) */
    public RenderParams params() {
        return params;
    }

    /** @return {@code true} (défaut) pour demander la conversion en PDF/A-1B */
    public boolean pdfa() {
        return pdfa;
    }

    @Override
    public String toString() {
        return "RenderOptions[" + params + ", pdfa=" + pdfa + "]";
    }

    public static final class Builder {
        private String lang;
        private String country;
        private String currency;
        private String timezone;
        private String dateStyle;
        private String numberStyle;
        private boolean pdfa = true;

        private Builder() {
        }

        /** @param lang {@code en}, {@code fr}, {@code es}, {@code it} ou {@code de} */
        public Builder lang(final String lang) {
            this.lang = lang;
            return this;
        }

        /** @param country code pays ISO 3166 alpha-2 */
        public Builder country(final String country) {
            this.country = country;
            return this;
        }

        /** @param currency code devise ISO 4217 */
        public Builder currency(final String currency) {
            this.currency = currency;
            return this;
        }

        /** @param timezone fuseau IANA, par exemple {@code Europe/Zurich} */
        public Builder timezone(final String timezone) {
            this.timezone = timezone;
            return this;
        }

        /** @param dateStyle {@code short}, {@code medium}, {@code long}, {@code full} ou {@code iso} */
        public Builder dateStyle(final String dateStyle) {
            this.dateStyle = dateStyle;
            return this;
        }

        /** @param numberStyle {@code locale}, {@code swiss} ou {@code plain} */
        public Builder numberStyle(final String numberStyle) {
            this.numberStyle = numberStyle;
            return this;
        }

        public Builder pdfa(final boolean pdfa) {
            this.pdfa = pdfa;
            return this;
        }

        public RenderOptions build() {
            return new RenderOptions(new RenderParams(lang, country, currency, timezone, dateStyle, numberStyle), pdfa);
        }
    }
}

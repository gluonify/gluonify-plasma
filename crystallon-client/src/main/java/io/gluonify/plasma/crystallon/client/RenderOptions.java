package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.crystallon.RenderParams;

/**
 * Options of a render: the generation parameters ({@link RenderParams}) and the PDF/A-1B conversion. Immutable. {@link #DEFAULT}: PDF/A, and
 * no parameter requested: each takes the value of the template if it carries one ({@code params} at the template root), otherwise the server default
 * (the document language, country, currency... are derived from {@code lang}, English by default). A parameter set here always wins.
 */
public final class RenderOptions {

    /** PDF/A-1B, no parameter requested (those of the template, then of the server). */
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

    /** @return the generation parameters; never {@code null} (absent fields are {@code null}) */
    public RenderParams params() {
        return params;
    }

    /** @return {@code true} (default) to request the PDF/A-1B conversion */
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

        /** @param lang {@code en}, {@code fr}, {@code es}, {@code it} or {@code de} */
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

        /** @param timezone IANA time zone, for example {@code Europe/Zurich} */
        public Builder timezone(final String timezone) {
            this.timezone = timezone;
            return this;
        }

        /** @param dateStyle {@code short}, {@code medium}, {@code long}, {@code full} or {@code iso} */
        public Builder dateStyle(final String dateStyle) {
            this.dateStyle = dateStyle;
            return this;
        }

        /** @param numberStyle {@code locale}, {@code swiss} or {@code plain} */
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

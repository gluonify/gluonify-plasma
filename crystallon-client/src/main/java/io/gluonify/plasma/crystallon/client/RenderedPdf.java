package io.gluonify.plasma.crystallon.client;

import java.util.Arrays;

/**
 * Un document rendu.
 *
 * @param bytes    le PDF (commence par {@code %PDF}) ; le tableau n'est pas copié
 * @param pages    nombre de pages ({@code X-Crystallon-Pages}), 0 si l'en-tête manque
 * @param warnings nombre d'avertissements du rendu ({@code X-Crystallon-Warnings}), 0 si l'en-tête manque ; le détail vient de
 *                 {@link TemplateRef#validate(Object)}
 * @param pdfa     verdict PDF/A ({@code X-Crystallon-PDFA} : {@code valid} ou {@code invalid}), {@code null} si l'en-tête est absent
 */
public record RenderedPdf(byte[] bytes, int pages, int warnings, Boolean pdfa) {

    @Override
    public boolean equals(final Object o) {
        return o instanceof RenderedPdf other && pages == other.pages && warnings == other.warnings && java.util.Objects.equals(pdfa, other.pdfa)
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Arrays.hashCode(bytes) + pages) + warnings) + java.util.Objects.hashCode(pdfa);
    }

    @Override
    public String toString() {
        return "RenderedPdf[bytes=" + (bytes == null ? "null" : bytes.length + " bytes") + ", pages=" + pages + ", warnings=" + warnings + ", pdfa=" + pdfa + "]";
    }
}

package io.gluonify.plasma.crystallon.client;

import java.util.Arrays;

/**
 * Un document rendu.
 *
 * @param bytes    the PDF (starts with {@code %PDF}); the array is not copied
 * @param pages    number of pages ({@code X-Crystallon-Pages}), 0 if the header is missing
 * @param warnings number of render warnings ({@code X-Crystallon-Warnings}), 0 if the header is missing; the details come from
 *                 {@link TemplateRef#validate(Object)}
 * @param pdfa     PDF/A verdict ({@code X-Crystallon-PDFA}: {@code valid} or {@code invalid}), {@code null} if the header is absent
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

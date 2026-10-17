package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An error or a warning from the validation of a template.
 *
 * @param code    {@code CRYSTALLON-00NN} for an error, {@code null} for a warning
 * @param message message in English
 * @param path    location of the problem in the template JSON (key names and {@code [index]}, for example {@code body[3].blocks[0].text}),
 *                {@code null} when the problem concerns the whole template
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Issue(String code, String message, String path) {
}

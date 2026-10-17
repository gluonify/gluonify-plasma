package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Error body of all Crystallon endpoints.
 *
 * @param code    stable code, for example {@code CRYSTALLON-0005}
 * @param message message in English, safe to display
 * @param path    for template problems: location in the template JSON; absent otherwise
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorBody(String code, String message, String path) {
}

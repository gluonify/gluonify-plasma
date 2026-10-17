package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Body of a Crystallon render or validation request. {@code data} is never {@code null}: without data, it is the empty object
 * {@code {}} (a body without {@code data} would make the server render the template's example). A {@code null} {@code params} is omitted (parameters of the template, then of the server).
 *
 * @param data   the document payload (a JSON object once serialized: record, {@link Map}, bean)
 * @param params the generation parameters, or {@code null} for those of the template then of the server
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenderRequest(Object data, RenderParams params) {

    public RenderRequest {
        if (data == null) {
            data = Map.of();
        }
    }
}

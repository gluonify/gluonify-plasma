package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/**
 * Body of a request to render or validate a template sent with the request ({@code POST /v1/render}, {@code POST /v1/render/validate}).
 * Like {@link RenderRequest}, but the template is part of the body instead of being stored in Crystallon. {@code data} is never
 * {@code null}: without data, it is the empty object {@code {}} (a body without {@code data} would make the server render the template's example).
 * A {@code null} {@code params} is omitted.
 *
 * @param template the name of a predefined template (a string: {@code invoice}, {@code letter}, {@code quote}), or the template itself (a JSON object
 *                 once serialized: {@link Map}, record, bean), possibly with {@code extends}; never {@code null}
 * @param data     the document payload (a JSON object once serialized: record, {@link Map}, bean)
 * @param params   the generation parameters, or {@code null} for those of the template then of the server
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InlineRenderRequest(Object template, Object data, RenderParams params) {

    /** @throws NullPointerException if {@code template} is {@code null} */
    public InlineRenderRequest {
        Objects.requireNonNull(template, "template");
        if (data == null) {
            data = Map.of();
        }
    }
}

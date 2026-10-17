package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Response of a validation ({@code .../render/validate}): do the template and the payload render without error? The lists are never
 * {@code null} (a list absent from the response becomes an empty list) and are unmodifiable.
 *
 * @param valid          the rendering succeeded
 * @param pages          number of pages when the rendering succeeded
 * @param warnings       rendering warnings (unknown paths...), messages only
 * @param errors         the errors, with the location of the problem, when {@code valid} is false
 * @param warningDetails the same warnings, each with the location that raised it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Check(boolean valid, int pages, List<String> warnings, List<Issue> errors, List<Issue> warningDetails) {

    public Check {
        warnings = frozen(warnings);
        errors = frozen(errors);
        warningDetails = frozen(warningDetails);
    }

    private static <T> List<T> frozen(final List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}

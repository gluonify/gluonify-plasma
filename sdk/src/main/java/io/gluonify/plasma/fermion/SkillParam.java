package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A typed parameter of a skill's contract.
 *
 * @param name       key in the payload (a dotted name, for example {@code infos.status}, constrains a nested field)
 * @param type       {@code STRING}, {@code BOOLEAN}, {@code INTEGER}, {@code LONG}, {@code OBJECT}…
 * @param required   mandatory or not
 * @param store      an output is kept in the workflow context
 * @param docUrl     optional URL that documents the type
 * @param enumValues for a text output with fixed values: the exhaustive set (workflow validation then rejects a comparison with an unknown literal); empty = not declared
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillParam(String name, String type, boolean required, boolean store, String docUrl, List<String> enumValues) {
    public static SkillParam of(String name, String type) {
        return new SkillParam(name, type, true, true, null, null);
    }

    public static SkillParam optional(String name, String type) {
        return new SkillParam(name, type, false, true, null, null);
    }
}

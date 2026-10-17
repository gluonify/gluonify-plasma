package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a node can do: the skill name is the action sent by {@link FermionMsg.Command}. {@code expectedDuration} (optional,
 * ISO-8601 duration: {@code PT2H}, {@code P7D}) is the longest time the task may stay without a result or {@link FermionMsg.Progress}
 * before fermion considers it lost and sends it again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs,
                              String expectedDuration) implements Descriptor {
    public SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs) {
        this(id, translations, inputs, outputs, null);
    }
}

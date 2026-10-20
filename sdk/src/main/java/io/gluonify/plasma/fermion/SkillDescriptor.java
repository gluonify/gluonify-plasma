package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a node can do: the skill name is the action sent by {@link FermionMsg.Command}. {@code expectedDuration} (optional,
 * ISO-8601 duration: {@code PT2H}, {@code P7D}) is the longest time the task may stay without a result or {@link FermionMsg.Progress}
 * before fermion considers it lost and sends it again. {@code requires} (optional, extension E4) is the number of slots of the node's
 * {@link NodeCapacity} that one task of this skill holds while it runs ({@code heavy: 1}); empty is normalised to {@code null}.
 *
 * @param id               the skill name
 * @param translations     localized descriptions
 * @param inputs           input parameters
 * @param outputs          output parameters
 * @param expectedDuration longest silence before a resend, ISO-8601, or {@code null}
 * @param requires         slots held per task, or {@code null}
 * @author David CABILLIC
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs,
                              String expectedDuration, Map<String, Integer> requires) implements Descriptor {

    /**
     * Normalises an empty {@code requires} to {@code null}, so that a skill without requirement keeps its former JSON, and sorts
     * the resource names (stable JSON).
     *
     * @param id               the skill name
     * @param translations     localized descriptions
     * @param inputs           input parameters
     * @param outputs          output parameters
     * @param expectedDuration longest silence before a resend, or {@code null}
     * @param requires         slots held per task, or {@code null}
     */
    public SkillDescriptor {

        requires = requires == null || requires.isEmpty() ? null : Collections.unmodifiableMap(new TreeMap<>(requires));
    }

    /**
     * Skill with a duration and no slot requirement.
     *
     * @param id               the skill name
     * @param translations     localized descriptions
     * @param inputs           input parameters
     * @param outputs          output parameters
     * @param expectedDuration longest silence before a resend, or {@code null}
     */
    public SkillDescriptor(final String id, final List<Translation> translations, final List<SkillParam> inputs, final List<SkillParam> outputs,
                           final String expectedDuration) {
        this(id, translations, inputs, outputs, expectedDuration, null);
    }

    /**
     * Skill without duration nor slot requirement.
     *
     * @param id           the skill name
     * @param translations localized descriptions
     * @param inputs       input parameters
     * @param outputs      output parameters
     */
    public SkillDescriptor(final String id, final List<Translation> translations, final List<SkillParam> inputs, final List<SkillParam> outputs) {
        this(id, translations, inputs, outputs, null, null);
    }

    /**
     * Slots held by one task of this skill (never {@code null}).
     *
     * @return the requirement per resource name, empty when none
     */
    public Map<String, Integer> requirements() {

        return requires == null ? Map.of() : requires;
    }
}

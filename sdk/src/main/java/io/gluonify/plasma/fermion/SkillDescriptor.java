package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Ce qu'un nœud sait faire : le nom de la compétence est l'action envoyée par {@link FermionMsg.Command}. {@code expectedDuration} (facultative,
 * durée ISO-8601 : {@code PT2H}, {@code P7D}) est le plus long délai pendant lequel la tâche peut rester sans résultat ni {@link FermionMsg.Progress}
 * sans que fermion la juge perdue et la renvoie.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs,
                              String expectedDuration) implements Descriptor {
    public SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs) {
        this(id, translations, inputs, outputs, null);
    }
}

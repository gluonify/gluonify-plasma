package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Ce qu'un nœud sait faire : le nom de la compétence est l'action envoyée par {@link FermionMsg.Command}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDescriptor(String id, List<Translation> translations, List<SkillParam> inputs, List<SkillParam> outputs) implements Descriptor {}

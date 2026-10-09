package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Un workflow qu'un nœud déclare utiliser, avec ses noms localisés. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDescriptor(String id, List<Translation> translations, List<SkillParam> inputs) implements Descriptor {}

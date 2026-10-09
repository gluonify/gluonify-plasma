package io.gluonify.plasma.fermion;

import java.util.List;

/** Description commune d'une compétence ou d'un workflow : identifiant, noms localisés, paramètres d'entrée. */
public interface Descriptor {
    String id();

    List<Translation> translations();

    List<SkillParam> inputs();
}

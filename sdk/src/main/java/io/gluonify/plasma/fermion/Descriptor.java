package io.gluonify.plasma.fermion;

import java.util.List;

/** Common description of a skill or a workflow: identifier, localized names, input parameters. */
public interface Descriptor {
    String id();

    List<Translation> translations();

    List<SkillParam> inputs();
}

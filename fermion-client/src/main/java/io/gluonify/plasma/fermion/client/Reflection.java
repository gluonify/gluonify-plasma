package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.Descriptor;
import io.gluonify.plasma.fermion.FermionMsg;
import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.SkillDescriptor;
import io.gluonify.plasma.fermion.SkillParam;
import io.gluonify.plasma.fermion.Translation;
import io.gluonify.plasma.fermion.WorkflowDescriptor;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Types (de)serialized by Jackson, to keep for the native image. */
@RegisterForReflection(targets = {
        FermionMsg.class, FermionMsg.Auth.class, FermionMsg.Hello.class, FermionMsg.Start.class, FermionMsg.Command.class,
        FermionMsg.Result.class, FermionMsg.Progress.class, FermionMsg.Error.class, Descriptor.class, SkillDescriptor.class, WorkflowDescriptor.class,
        SkillParam.class, Translation.class, Language.class
})
public final class Reflection {
    private Reflection() {
    }
}

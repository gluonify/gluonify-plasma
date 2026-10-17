package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.SkillParam;
import io.gluonify.plasma.fermion.Translation;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A skill that the node knows how to run: {@link #action()} is the name of the action sent by fermion. Implementation = CDI bean
 * (for example {@code @ApplicationScoped}); {@link SkillHandlerRegistry} discovers them and announces them in {@code Hello}.
 */
public interface SkillHandler {

    /** Name of the skill (= action of the task). */
    String action();

    default List<SkillParam> inputs() {
        return List.of();
    }

    default List<SkillParam> outputs() {
        return List.of();
    }

    /** Localized descriptions: empty, or all five languages (see {@link #translations}). */
    default List<Translation> descriptions() {
        return List.of();
    }

    /**
     * Longest time fermion may stay without a result or heartbeat from this task before considering it lost (announced in
     * {@code Hello}). {@code null}: the fermion timeout ({@code gluonify.fermion.dispatch.result-timeout}). For a wait of several
     * days (human validation), announce it here: the automatic heartbeat already covers a running task, not a restarted node.
     */
    default Duration expectedDuration() {
        return null;
    }

    /**
     * Runs the task (off the event thread).
     *
     * @return variables to merge into the workflow state (may be {@code null})
     * @throws SkillFailureException for a failure with {@code errorKind}; any other exception is a failure without kind
     */
    Map<String, Object> handle(TaskContext context) throws Exception;

    /** The five translations of a text, in the order en, fr, es, it, de. */
    static List<Translation> translations(final String en, final String fr, final String es, final String it, final String de) {
        return List.of(new Translation(Language.EN, en), new Translation(Language.FR, fr), new Translation(Language.ES, es),
                new Translation(Language.IT, it), new Translation(Language.DE, de));
    }
}

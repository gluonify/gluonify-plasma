package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.SkillDescriptor;
import io.gluonify.plasma.fermion.Translation;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Collecte les beans {@link SkillHandler}, les indexe par action et fournit les compétences du {@code Hello}. */
@ApplicationScoped
public class SkillHandlerRegistry {

    private final Map<String, SkillHandler> byAction = new TreeMap<>();

    @Inject
    Instance<SkillHandler> handlers;

    @PostConstruct
    void index() {
        for (final SkillHandler handler : handlers) {
            final String action = handler.action();
            if (action == null || action.isBlank()) {
                throw new IllegalStateException("SkillHandler without action: " + handler.getClass().getName());
            }
            checkDescriptions(handler);
            final SkillHandler previous = byAction.put(action, handler);
            if (previous != null) {
                Log.warnv("fermion-client=handler action={0} duplicate, {1} replaces {2}", action, handler.getClass().getName(), previous.getClass().getName());
            }
        }
        Log.infov("fermion-client=handlers skills={0}", byAction.keySet());
    }

    /** Des descriptions partielles sont une erreur : toutes les langues ou aucune. */
    private static void checkDescriptions(final SkillHandler handler) {
        final List<Translation> descriptions = handler.descriptions();
        if (descriptions == null || descriptions.isEmpty()) {
            Log.warnv("fermion-client=handler action={0} has no localized description", handler.action());
            return;
        }
        final Set<Language> present = descriptions.stream().map(Translation::language).collect(Collectors.toCollection(() -> EnumSet.noneOf(Language.class)));
        if (!present.containsAll(EnumSet.allOf(Language.class))) {
            throw new IllegalStateException("SkillHandler " + handler.action() + ": descriptions missing for " + EnumSet.complementOf(EnumSet.copyOf(present)));
        }
    }

    public List<SkillDescriptor> skills() {
        final List<SkillDescriptor> skills = new ArrayList<>(byAction.size());
        for (final SkillHandler h : byAction.values()) {
            final java.time.Duration expected = h.expectedDuration();
            skills.add(new SkillDescriptor(h.action(), h.descriptions() == null ? List.of() : h.descriptions(), h.inputs(), h.outputs(),
                    expected == null ? null : expected.toString()));
        }
        return skills;
    }

    public SkillHandler get(final String action) {
        return byAction.get(action);
    }
}

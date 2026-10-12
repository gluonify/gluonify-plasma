package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.SkillParam;
import io.gluonify.plasma.fermion.Translation;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Une compétence que le nœud sait exécuter : {@link #action()} est le nom de l'action envoyée par fermion. Implémentation = bean CDI
 * (par exemple {@code @ApplicationScoped}) ; {@link SkillHandlerRegistry} les découvre et les annonce dans {@code Hello}.
 */
public interface SkillHandler {

    /** Nom de la compétence (= action de la tâche). */
    String action();

    default List<SkillParam> inputs() {
        return List.of();
    }

    default List<SkillParam> outputs() {
        return List.of();
    }

    /** Descriptions localisées : vide, ou les cinq langues (voir {@link #translations}). */
    default List<Translation> descriptions() {
        return List.of();
    }

    /**
     * Plus long délai pendant lequel fermion peut rester sans résultat ni battement de cette tâche sans la juger perdue (annoncé dans
     * {@code Hello}). {@code null} : le délai de fermion ({@code gluonify.fermion.dispatch.result-timeout}). Pour une attente de plusieurs
     * jours (validation par un humain), l'annoncer ici : le battement automatique couvre déjà une tâche qui tourne, pas un nœud redémarré.
     */
    default Duration expectedDuration() {
        return null;
    }

    /**
     * Exécute la tâche (hors du thread d'événements).
     *
     * @return variables à fusionner dans l'état du workflow (peut être {@code null})
     * @throws SkillFailureException pour un échec avec {@code errorKind} ; toute autre exception est un échec sans genre
     */
    Map<String, Object> handle(TaskContext context) throws Exception;

    /** Les cinq traductions d'un texte, dans l'ordre en, fr, es, it, de. */
    static List<Translation> translations(final String en, final String fr, final String es, final String it, final String de) {
        return List.of(new Translation(Language.EN, en), new Translation(Language.FR, fr), new Translation(Language.ES, es),
                new Translation(Language.IT, it), new Translation(Language.DE, de));
    }
}

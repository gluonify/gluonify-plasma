package io.gluonify.plasma.crystallon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Réponse d'une validation ({@code .../render/validate}) : le modèle et le payload se rendent-ils sans erreur ? Les listes ne sont jamais
 * {@code null} (une liste absente de la réponse devient une liste vide) et ne sont pas modifiables.
 *
 * @param valid          le rendu a réussi
 * @param pages          nombre de pages quand le rendu a réussi
 * @param warnings       avertissements du rendu (chemins inconnus...), messages seuls
 * @param errors         les erreurs, avec l'endroit du problème, quand {@code valid} est faux
 * @param warningDetails les mêmes avertissements, chacun avec l'endroit qui l'a levé
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Check(boolean valid, int pages, List<String> warnings, List<Issue> errors, List<Issue> warningDetails) {

    public Check {
        warnings = frozen(warnings);
        errors = frozen(errors);
        warningDetails = frozen(warningDetails);
    }

    private static <T> List<T> frozen(final List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}

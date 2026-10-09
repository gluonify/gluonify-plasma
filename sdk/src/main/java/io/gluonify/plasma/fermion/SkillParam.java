package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Un paramètre typé du contrat d'une compétence.
 *
 * @param name       clé dans la charge utile (un nom pointé, par exemple {@code infos.status}, contraint un champ imbriqué)
 * @param type       {@code STRING}, {@code BOOLEAN}, {@code INTEGER}, {@code LONG}, {@code OBJECT}…
 * @param required   obligatoire ou non
 * @param store      une sortie est conservée dans le contexte du workflow
 * @param docUrl     URL facultative qui documente le type
 * @param enumValues pour une sortie texte à valeurs fixes : l'ensemble exhaustif (la validation d'un workflow refuse alors une comparaison à un littéral inconnu) ; vide = non déclaré
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillParam(String name, String type, boolean required, boolean store, String docUrl, List<String> enumValues) {
    public static SkillParam of(String name, String type) {
        return new SkillParam(name, type, true, true, null, null);
    }

    public static SkillParam optional(String name, String type) {
        return new SkillParam(name, type, false, true, null, null);
    }
}

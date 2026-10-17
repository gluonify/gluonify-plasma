package io.gluonify.plasma.usage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validateur du contrat d'usage {@code gluonify-usage/1} ({@code usage-mcp.md} § 3.2) : utilisé par les tests de chaque composant et par
 * {@code check-usage-contract.sh}. Renvoie la liste des écarts (vide = conforme), n'écrit rien. Un champ inconnu est toléré (contrat additif).
 * Règles : {@code process} obligatoire ; {@code attribution} connue ; {@code none} sans ligne {@code byApp} ; au plus {@link UsageReport#MAX_APPS} lignes
 * plus une ligne {@link UsageReport#OTHER} ; chaque ligne identifiée avec {@code basis} connu ; et Σ CPU de {@code byApp} + {@code unattributedShare} =
 * {@code process.cpuSecondsTotal} à l'arrondi près. Ne détecte pas un secret : les tests de composant cherchent leurs valeurs de configuration.
 */
public final class UsageContract {
    /** Taille maximale d'une réponse. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Set<String> ATTRIBUTIONS = Set.of(UsageReport.NONE, UsageReport.MEASURED, UsageReport.ESTIMATED, UsageReport.PRORATA);
    private static final Set<String> BASES = ATTRIBUTIONS;
    private static final Set<String> MEMORY_KINDS = Set.of("rss", "pss", "cgroup");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private UsageContract() {}

    /** Valide un corps de réponse : taille bornée, JSON valide, puis {@link #check(JsonNode)}. */
    public static List<String> check(final byte[] body) {
        if (body == null) {
            return List.of("réponse absente");
        }
        if (body.length > MAX_BYTES) {
            return List.of("réponse de " + body.length + " octets (maximum " + MAX_BYTES + ")");
        }
        try {
            return check(MAPPER.readTree(new String(body, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return List.of("JSON illisible");
        }
    }

    public static List<String> check(final JsonNode root) {
        final List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return List.of("la réponse n'est pas un objet JSON");
        }
        if (!UsageReport.CONTRACT.equals(text(root, "contract"))) {
            errors.add("contract : " + UsageReport.CONTRACT + " attendu");
        }
        if (text(root, "component") == null || text(root, "component").isBlank()) {
            errors.add("component absent");
        }
        if (!root.path("at").isIntegralNumber() || root.path("at").asLong() < 0) {
            errors.add("at : entier ≥ 0 attendu");
        }
        final JsonNode process = root.path("process");
        Double cpuTotal = null;
        if (!process.isObject()) {
            errors.add("process obligatoire");
        } else {
            if (!isNumber(process, "cpuSecondsTotal")) {
                errors.add("process.cpuSecondsTotal : nombre ≥ 0 attendu");
            } else {
                cpuTotal = process.get("cpuSecondsTotal").asDouble();
            }
            if (!isInteger(process, "memoryBytes")) {
                errors.add("process.memoryBytes : entier ≥ 0 attendu");
            }
            if (!known(MEMORY_KINDS, text(process, "memoryKind"))) {
                errors.add("process.memoryKind : rss, pss ou cgroup attendu");
            }
            optionalInteger(process, "heapBytes", "process.", errors);
            optionalInteger(process, "threads", "process.", errors);
            optionalInteger(process, "uptimeSeconds", "process.", errors);
        }
        final JsonNode cgroup = root.get("cgroup");
        if (cgroup != null && !cgroup.isNull()) {
            if (!cgroup.isObject()) {
                errors.add("cgroup : objet attendu");
            } else {
                if (cgroup.has("cpuSecondsTotal") && !isNumber(cgroup, "cpuSecondsTotal")) {
                    errors.add("cgroup.cpuSecondsTotal : nombre ≥ 0 attendu");
                }
                optionalInteger(cgroup, "memoryBytes", "cgroup.", errors);
                optionalInteger(cgroup, "memoryLimitBytes", "cgroup.", errors);
            }
        }
        final String attribution = text(root, "attribution");
        if (!known(ATTRIBUTIONS, attribution)) {
            errors.add("attribution : none, measured, estimated ou prorata attendu");
        }
        final JsonNode byApp = root.path("byApp");
        if (!byApp.isArray()) {
            errors.add("byApp : tableau attendu (vide si attribution = none)");
            return errors;
        }
        if (UsageReport.NONE.equals(attribution) && !byApp.isEmpty()) {
            errors.add("attribution = none avec des lignes byApp");
        }
        int rows = 0;
        int others = 0;
        double sum = 0;
        for (final JsonNode row : byApp) {
            if (!row.isObject()) {
                errors.add("byApp : objet attendu");
                continue;
            }
            final String app = text(row, "app");
            if (app == null || app.isBlank()) {
                errors.add("byApp : app absent");
            } else if (UsageReport.OTHER.equals(app)) {
                others++;
            } else {
                rows++;
            }
            if (!isNumber(row, "cpuSeconds")) {
                errors.add("byApp[" + app + "].cpuSeconds : nombre ≥ 0 attendu");
            } else {
                sum += row.get("cpuSeconds").asDouble();
            }
            if (!isInteger(row, "memoryBytes")) {
                errors.add("byApp[" + app + "].memoryBytes : entier ≥ 0 attendu");
            }
            if (!known(BASES, text(row, "basis"))) {
                errors.add("byApp[" + app + "].basis : none, measured, estimated ou prorata attendu");
            }
        }
        if (rows > UsageReport.MAX_APPS) {
            errors.add("byApp : " + rows + " lignes (maximum " + UsageReport.MAX_APPS + ", puis une ligne " + UsageReport.OTHER + ")");
        }
        if (others > 1) {
            errors.add("byApp : une seule ligne " + UsageReport.OTHER);
        }
        if (!UsageReport.NONE.equals(attribution) && attribution != null && ATTRIBUTIONS.contains(attribution)) {
            final JsonNode rest = root.path("unattributedShare");
            if (!rest.isObject() || !isNumber(rest, "cpuSeconds") || !isInteger(rest, "memoryBytes")) {
                errors.add("unattributedShare : {cpuSeconds, memoryBytes} attendu");
            } else if (cpuTotal != null) {
                final double tolerance = 0.01 + 0.001 * byApp.size();
                if (Math.abs(sum + rest.get("cpuSeconds").asDouble() - cpuTotal) > tolerance) {
                    errors.add("Σ byApp.cpuSeconds + unattributedShare.cpuSeconds ≠ process.cpuSecondsTotal");
                }
            }
        }
        return errors;
    }

    private static boolean known(final Set<String> values, final String value) {
        return value != null && values.contains(value);
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean isNumber(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() >= 0;
    }

    private static boolean isInteger(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isIntegralNumber() && v.asLong() >= 0;
    }

    private static void optionalInteger(final JsonNode node, final String field, final String prefix, final List<String> errors) {
        final JsonNode v = node.get(field);
        if (v != null && !v.isNull() && !isInteger(node, field)) {
            errors.add(prefix + field + " : entier ≥ 0 attendu");
        }
    }
}

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
 * Règles : {@code process} obligatoire ; {@code attribution} connue ; {@code none} sans ligne {@code byApp} ; chaque ligne identifiée avec un
 * {@code basis} parmi {@code measured}, {@code estimated}, {@code prorata} (jamais {@code none} : une ligne est toujours attribuée d'une façon) ; et
 * Σ CPU de {@code byApp} + {@code unattributedShare} = {@code process.cpuSecondsTotal} à l'arrondi près.
 * <p><b>Plafond</b> : au plus {@link UsageReport#MAX_APPS} (500) lignes d'applications réelles (les plus grosses, par mémoire puis CPU), <b>plus une seule</b>
 * ligne {@link UsageReport#OTHER} ({@code _other}) qui regroupe le reste ; {@code _other} ne compte pas dans les 500. Même lecture pour tous les dépôts.
 * Ne détecte pas un secret : les tests de composant cherchent leurs valeurs de configuration ({@code UsageContractTests.secrets()}).
 */
public final class UsageContract {
    /** Taille maximale d'une réponse. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Set<String> ATTRIBUTIONS = Set.of(UsageReport.NONE, UsageReport.MEASURED, UsageReport.ESTIMATED, UsageReport.PRORATA);
    /** {@code basis} d'une ligne {@code byApp} : jamais {@code none}. */
    private static final Set<String> BASES = Set.of(UsageReport.MEASURED, UsageReport.ESTIMATED, UsageReport.PRORATA);
    private static final Set<String> MEMORY_KINDS = Set.of("rss", "pss", "cgroup");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String CPU_SECONDS_TOTAL = "cpuSecondsTotal";
    private static final String CPU_SECONDS = "cpuSeconds";
    private static final String MEMORY_BYTES = "memoryBytes";
    private static final String PROCESS_PREFIX = "process.";
    private static final String CGROUP_PREFIX = "cgroup.";
    private static final String BY_APP_PREFIX = "byApp[";
    private static final String NUMBER_EXPECTED = " : nombre ≥ 0 attendu";

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

    /** Les écarts au contrat d'une réponse déjà lue ; vide si elle est conforme. */
    public static List<String> check(final JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of("la réponse n'est pas un objet JSON");
        }
        final List<String> errors = new ArrayList<>();
        checkIdentity(root, errors);
        final Double cpuTotal = checkProcess(root.path("process"), errors);
        checkCgroup(root.get("cgroup"), errors);
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
        final double sum = checkRows(byApp, errors);
        if (known(ATTRIBUTIONS, attribution) && !UsageReport.NONE.equals(attribution)) {
            checkUnattributed(root.path("unattributedShare"), sum, cpuTotal, byApp.size(), errors);
        }
        return errors;
    }

    /** {@code contract}, {@code component}, {@code at}. */
    private static void checkIdentity(final JsonNode root, final List<String> errors) {
        if (!UsageReport.CONTRACT_ID.equals(text(root, "contract"))) {
            errors.add("contract : " + UsageReport.CONTRACT_ID + " attendu");
        }
        final String component = text(root, "component");
        if (component == null || component.isBlank()) {
            errors.add("component absent");
        }
        if (!root.path("at").isIntegralNumber() || root.path("at").asLong() < 0) {
            errors.add("at : entier ≥ 0 attendu");
        }
    }

    /** {@code process} obligatoire ; rend {@code cpuSecondsTotal} s'il est lisible. */
    private static Double checkProcess(final JsonNode process, final List<String> errors) {
        if (!process.isObject()) {
            errors.add("process obligatoire");
            return null;
        }
        Double cpuTotal = null;
        if (!isNumber(process, CPU_SECONDS_TOTAL)) {
            errors.add(PROCESS_PREFIX + CPU_SECONDS_TOTAL + NUMBER_EXPECTED);
        } else {
            cpuTotal = process.get(CPU_SECONDS_TOTAL).asDouble();
        }
        if (!isInteger(process, MEMORY_BYTES)) {
            errors.add(PROCESS_PREFIX + MEMORY_BYTES + " : entier ≥ 0 attendu");
        }
        if (!known(MEMORY_KINDS, text(process, "memoryKind"))) {
            errors.add(PROCESS_PREFIX + "memoryKind : rss, pss ou cgroup attendu");
        }
        optionalInteger(process, "heapBytes", PROCESS_PREFIX, errors);
        optionalInteger(process, "threads", PROCESS_PREFIX, errors);
        optionalInteger(process, "uptimeSeconds", PROCESS_PREFIX, errors);
        return cpuTotal;
    }

    /** {@code cgroup} facultatif ; s'il est là, un objet aux champs numériques. */
    private static void checkCgroup(final JsonNode cgroup, final List<String> errors) {
        if (cgroup == null || cgroup.isNull()) {
            return;
        }
        if (!cgroup.isObject()) {
            errors.add("cgroup : objet attendu");
            return;
        }
        if (cgroup.has(CPU_SECONDS_TOTAL) && !isNumber(cgroup, CPU_SECONDS_TOTAL)) {
            errors.add(CGROUP_PREFIX + CPU_SECONDS_TOTAL + NUMBER_EXPECTED);
        }
        optionalInteger(cgroup, MEMORY_BYTES, CGROUP_PREFIX, errors);
        optionalInteger(cgroup, "memoryLimitBytes", CGROUP_PREFIX, errors);
    }

    /** Chaque ligne {@code byApp}, le plafond et l'unicité de {@code _other} ; rend la somme des CPU des lignes. */
    private static double checkRows(final JsonNode byApp, final List<String> errors) {
        int apps = 0;
        int others = 0;
        double cpu = 0;
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
                apps++;
            }
            cpu += checkRow(row, app, errors);
        }
        if (apps > UsageReport.MAX_APPS) {
            errors.add("byApp : " + apps + " lignes (maximum " + UsageReport.MAX_APPS + ", puis une ligne " + UsageReport.OTHER + ")");
        }
        if (others > 1) {
            errors.add("byApp : une seule ligne " + UsageReport.OTHER);
        }
        return cpu;
    }

    /** Les champs d'une ligne ; rend son CPU (0 s'il est illisible). */
    private static double checkRow(final JsonNode row, final String app, final List<String> errors) {
        double cpu = 0;
        if (!isNumber(row, CPU_SECONDS)) {
            errors.add(BY_APP_PREFIX + app + "]." + CPU_SECONDS + NUMBER_EXPECTED);
        } else {
            cpu = row.get(CPU_SECONDS).asDouble();
        }
        if (!isInteger(row, MEMORY_BYTES)) {
            errors.add(BY_APP_PREFIX + app + "]." + MEMORY_BYTES + " : entier ≥ 0 attendu");
        }
        if (!known(BASES, text(row, "basis"))) {
            errors.add(BY_APP_PREFIX + app + "].basis : measured, estimated ou prorata attendu");
        }
        return cpu;
    }

    /** {@code unattributedShare} exigé dès qu'il y a une attribution, et Σ parts + reste = total à l'arrondi près. */
    private static void checkUnattributed(final JsonNode rest, final double sum, final Double cpuTotal, final int lines, final List<String> errors) {
        if (!rest.isObject() || !isNumber(rest, CPU_SECONDS) || !isInteger(rest, MEMORY_BYTES)) {
            errors.add("unattributedShare : {cpuSeconds, memoryBytes} attendu");
            return;
        }
        if (cpuTotal != null) {
            final double tolerance = 0.01 + 0.001 * lines;
            if (Math.abs(sum + rest.get(CPU_SECONDS).asDouble() - cpuTotal) > tolerance) {
                errors.add("Σ byApp.cpuSeconds + unattributedShare.cpuSeconds ≠ process.cpuSecondsTotal");
            }
        }
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

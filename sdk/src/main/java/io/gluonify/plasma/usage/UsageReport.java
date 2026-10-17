package io.gluonify.plasma.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Réponse du contrat d'usage {@code gluonify-usage/1} ({@code usage-mcp.md} § 3.2), servie par {@code GET /q/usage} de chaque composant.
 * Additif : un lecteur ignore les champs inconnus ; {@link #CONTRACT_ID} ne change que pour une rupture. Les champs absents ne sont pas écrits
 * (jamais de valeur inventée). Le CPU est un compteur CUMULÉ en secondes, jamais un pourcentage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageReport(String contract, String component, String version, String instance, String node, long at, Process process, Cgroup cgroup,
        String attribution, List<AppShare> byApp, Share unattributedShare) {

    /** La valeur du champ {@code contract} : nom et version du contrat. */
    public static final String CONTRACT_ID = "gluonify-usage/1";

    /** {@code attribution} : rien à attribuer. */
    public static final String NONE = "none";
    /** {@code attribution} ou {@code basis} : compté à la source, sans estimation. */
    public static final String MEASURED = "measured";
    /** {@code attribution} ou {@code basis} : au prorata d'une grandeur mesurée (indicatif). */
    public static final String ESTIMATED = "estimated";
    /** {@code attribution} ou {@code basis} : au prorata du temps ou des requêtes (indicatif). */
    public static final String PRORATA = "prorata";

    /** Nombre maximal de lignes {@code byApp} d'applications réelles (la ligne {@link #OTHER} ne compte pas). */
    public static final int MAX_APPS = 500;

    /** Application fictive, une seule ligne, qui regroupe les lignes au-delà de {@link #MAX_APPS}. */
    public static final String OTHER = "_other";

    /** Le processus du composant : obligatoire. {@code memoryKind} : {@code rss}, {@code pss} ou {@code cgroup}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Process(Double cpuSecondsTotal, Long memoryBytes, String memoryKind, Long heapBytes, Integer threads, Long uptimeSeconds) {}

    /** Le cgroup v2 du processus, quand il existe ; {@code memoryLimitBytes} absent si la limite est {@code max}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cgroup(Double cpuSecondsTotal, Long memoryBytes, Long memoryLimitBytes) {}

    /** Part attribuable à une application (identifiée par son UUID, le nom sert à l'affichage). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppShare(String app, String uuid, double cpuSeconds, long memoryBytes, String basis, String unit) {
        public AppShare(final String app, final String uuid, final double cpuSeconds, final long memoryBytes, final String basis) {
            this(app, uuid, cpuSeconds, memoryBytes, basis, null);
        }
    }

    /** Ce qui n'est attribué à aucune application (la part fixe du composant). */
    public record Share(double cpuSeconds, long memoryBytes) {}

    /**
     * Assemble une réponse valide : ramène les parts à {@link #MAX_APPS} lignes (les plus grosses en mémoire, le reste dans {@link #OTHER}), les réduit
     * proportionnellement si leur somme dépasse le total du processus (ce sont des estimations), et calcule la part non attribuée pour que
     * {@code Σ byApp + unattributedShare = process} (CPU et mémoire). Sans part ({@code shares} vide ou {@code null}), l'attribution est {@link #NONE}.
     */
    public static UsageReport of(final String component, final String version, final String instance, final String node, final long at, final Process process,
            final Cgroup cgroup, final String attribution, final List<AppShare> shares) {
        if (shares == null || shares.isEmpty() || NONE.equals(attribution)) {
            return new UsageReport(CONTRACT_ID, component, version, instance, node, at, process, cgroup, NONE, List.of(), null);
        }
        final double totalCpu = process.cpuSecondsTotal() == null ? 0 : process.cpuSecondsTotal();
        final long totalMemory = process.memoryBytes() == null ? 0 : process.memoryBytes();
        final List<AppShare> rows = fitToTotal(fold(sorted(shares)), totalCpu, totalMemory);
        final Share rest = new Share(Math.max(0, totalCpu - sumCpu(rows)), Math.max(0, totalMemory - sumMemory(rows)));
        return new UsageReport(CONTRACT_ID, component, version, instance, node, at, process, cgroup, attribution == null ? ESTIMATED : attribution,
                List.copyOf(rows), rest);
    }

    /** Les parts, les plus grosses d'abord (mémoire, puis CPU, puis nom). */
    private static List<AppShare> sorted(final List<AppShare> shares) {
        final List<AppShare> rows = new ArrayList<>(shares);
        rows.sort(Comparator.comparingLong(AppShare::memoryBytes).reversed().thenComparing(Comparator.comparingDouble(AppShare::cpuSeconds).reversed())
                .thenComparing(AppShare::app));
        return rows;
    }

    /** Au-delà de {@link #MAX_APPS} lignes, le reste est fondu dans une ligne {@link #OTHER} ({@code basis} commun, sinon {@link #ESTIMATED}). */
    private static List<AppShare> fold(final List<AppShare> rows) {
        if (rows.size() <= MAX_APPS) {
            return rows;
        }
        final List<AppShare> rest = rows.subList(MAX_APPS, rows.size());
        String basis = rest.get(0).basis();
        for (final AppShare s : rest) {
            if (basis != null && !basis.equals(s.basis())) {
                basis = ESTIMATED;
            }
        }
        final List<AppShare> kept = new ArrayList<>(rows.subList(0, MAX_APPS));
        kept.add(new AppShare(OTHER, null, sumCpu(rest), sumMemory(rest), basis));
        return kept;
    }

    /** Des parts dont la somme dépasse le processus sont des estimations : réduites au prorata pour tenir dans le total. */
    private static List<AppShare> fitToTotal(final List<AppShare> rows, final double totalCpu, final long totalMemory) {
        final double sumCpu = sumCpu(rows);
        final long sumMemory = sumMemory(rows);
        final double cpuScale = sumCpu > totalCpu && sumCpu > 0 ? totalCpu / sumCpu : 1;
        final double memoryScale = sumMemory > totalMemory && sumMemory > 0 ? (double) totalMemory / sumMemory : 1;
        if (cpuScale == 1 && memoryScale == 1) {
            return rows;
        }
        final List<AppShare> out = new ArrayList<>(rows.size());
        for (final AppShare s : rows) {
            out.add(new AppShare(s.app(), s.uuid(), s.cpuSeconds() * cpuScale, (long) Math.floor(s.memoryBytes() * memoryScale), s.basis(), s.unit()));
        }
        return out;
    }

    private static double sumCpu(final List<AppShare> rows) {
        double cpu = 0;
        for (final AppShare s : rows) {
            cpu += s.cpuSeconds();
        }
        return cpu;
    }

    private static long sumMemory(final List<AppShare> rows) {
        long memory = 0;
        for (final AppShare s : rows) {
            memory += s.memoryBytes();
        }
        return memory;
    }
}

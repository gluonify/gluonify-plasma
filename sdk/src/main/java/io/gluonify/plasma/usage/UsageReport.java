package io.gluonify.plasma.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Réponse du contrat d'usage {@code gluonify-usage/1} ({@code usage-mcp.md} § 3.2), servie par {@code GET /q/usage} de chaque composant.
 * Additif : un lecteur ignore les champs inconnus ; {@link #CONTRACT} ne change que pour une rupture. Les champs absents ne sont pas écrits
 * (jamais de valeur inventée). Le CPU est un compteur CUMULÉ en secondes, jamais un pourcentage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageReport(String contract, String component, String version, String instance, String node, long at, Process process, Cgroup cgroup,
        String attribution, List<AppShare> byApp, Share unattributedShare) {

    public static final String CONTRACT = "gluonify-usage/1";

    /** {@code attribution} et {@code basis} : {@code none} (rien à attribuer), {@code measured} (compté à la source), {@code estimated}, {@code prorata}. */
    public static final String NONE = "none", MEASURED = "measured", ESTIMATED = "estimated", PRORATA = "prorata";

    /** Nombre maximal de lignes {@code byApp} (hors la ligne {@link #OTHER}). */
    public static final int MAX_APPS = 500;

    /** Application fictive qui regroupe les lignes au-delà de {@link #MAX_APPS}. */
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
        final List<AppShare> rows = shares == null ? List.of() : new ArrayList<>(shares);
        if (rows.isEmpty() || NONE.equals(attribution)) {
            return new UsageReport(CONTRACT, component, version, instance, node, at, process, cgroup, NONE, List.of(), null);
        }
        rows.sort(Comparator.comparingLong(AppShare::memoryBytes).reversed().thenComparing(Comparator.comparingDouble(AppShare::cpuSeconds).reversed())
                .thenComparing(AppShare::app));
        List<AppShare> kept = rows;
        if (rows.size() > MAX_APPS) {
            kept = new ArrayList<>(rows.subList(0, MAX_APPS));
            double cpu = 0;
            long memory = 0;
            String basis = rows.get(MAX_APPS).basis();
            for (final AppShare s : rows.subList(MAX_APPS, rows.size())) {
                cpu += s.cpuSeconds();
                memory += s.memoryBytes();
                if (basis != null && !basis.equals(s.basis())) {
                    basis = ESTIMATED;
                }
            }
            kept.add(new AppShare(OTHER, null, cpu, memory, basis));
        }
        final double totalCpu = process.cpuSecondsTotal() == null ? 0 : process.cpuSecondsTotal();
        final long totalMemory = process.memoryBytes() == null ? 0 : process.memoryBytes();
        double sumCpu = 0;
        long sumMemory = 0;
        for (final AppShare s : kept) {
            sumCpu += s.cpuSeconds();
            sumMemory += s.memoryBytes();
        }
        final double cpuScale = sumCpu > totalCpu && sumCpu > 0 ? totalCpu / sumCpu : 1;
        final double memoryScale = sumMemory > totalMemory && sumMemory > 0 ? (double) totalMemory / sumMemory : 1;
        final List<AppShare> out = new ArrayList<>(kept.size());
        double cpu = 0;
        long memory = 0;
        for (final AppShare s : kept) {
            final double c = s.cpuSeconds() * cpuScale;
            final long m = (long) Math.floor(s.memoryBytes() * memoryScale);
            out.add(new AppShare(s.app(), s.uuid(), c, m, s.basis(), s.unit()));
            cpu += c;
            memory += m;
        }
        return new UsageReport(CONTRACT, component, version, instance, node, at, process, cgroup, attribution == null ? ESTIMATED : attribution,
                List.copyOf(out), new Share(Math.max(0, totalCpu - cpu), Math.max(0, totalMemory - memory)));
    }
}

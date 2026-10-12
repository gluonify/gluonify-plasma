package io.gluonify.plasma.usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File d'attente durable des lots d'usage non acquittés ({@code metering.md} § 4.2), sur le disque LOCAL de l'émetteur (jamais sur {@code /distributed/*}) :
 * un fichier {@code b-<ms>-<n>-<count>.jsonl} par lot (une ligne JSON par enregistrement), écrit dans un temporaire puis déplacé (un lecteur ne voit jamais un lot à moitié écrit).
 * Bornée : au-delà de {@code maxRecords} enregistrements ou de {@code maxAgeMs}, les lots les plus anciens sont supprimés et COMPTÉS ({@link #dropped()}, persisté) :
 * la perte est en faveur du client, et visible au calorimètre par le trou de {@code seq}.
 * <p>Garde aussi la suite {@code seq} de l'émetteur (fichier {@code seq} : {@code <époque> <dernier>}, réécrit à chaque lot) : elle survit au redémarrage sans trou.
 * Sans dossier ({@code dir == null}), tout reste en mémoire (perdu au redémarrage, mêmes bornes).
 */
public final class UsageSpool implements UsageMeter.SeqSource {
    private static final Pattern NAME = Pattern.compile("b-(\\d+)-(\\d+)-(\\d+)\\.jsonl");

    private final Path dir;
    private final int maxRecords;
    private final long maxAgeMs;
    private final LongSupplier clock;
    private final AtomicLong dropped = new AtomicLong();
    private final List<Batch> memory = new ArrayList<>();
    private long counter;
    private long epoch, seq;

    /** Un lot en attente : ses lignes JSON, sa date d'écriture, et son fichier (null en mémoire). */
    public record Batch(long at, List<String> lines, Path file) {}

    public UsageSpool(Path dir, int maxRecords, long maxAgeMs, LongSupplier clock) {
        this.dir = dir;
        this.maxRecords = Math.max(1, maxRecords);
        this.maxAgeMs = Math.max(60_000, maxAgeMs);
        this.clock = clock;
        long now = clock.getAsLong();
        epoch = now;
        if (dir != null) {
            try {
                Files.createDirectories(dir);
                Path f = dir.resolve("seq");
                if (Files.isRegularFile(f)) {
                    String[] p = Files.readString(f, StandardCharsets.UTF_8).trim().split("\\s+");
                    epoch = Long.parseLong(p[0]);
                    seq = Long.parseLong(p[1]);
                }
                Path d = dir.resolve("dropped");
                if (Files.isRegularFile(d)) dropped.set(Long.parseLong(Files.readString(d, StandardCharsets.UTF_8).trim()));
                for (Batch b : pending()) counter = Math.max(counter, seqOf(b.file()) + 1);
            } catch (IOException | RuntimeException e) {
                epoch = now; // suite illisible : une nouvelle époque (le calorimètre ne cherche pas de trou entre deux époques)
                seq = 0;
            }
        }
    }

    @Override
    public synchronized long epoch() { return epoch; }

    @Override
    public synchronized long next() {
        return ++seq;
    }

    /** Enregistrements abandonnés depuis toujours (persisté), exposé en {@code gluonify_usage_dropped_total}. */
    public long dropped() { return dropped.get(); }

    /** Ajoute un lot (jamais d'exception : un disque en erreur garde le lot en mémoire). */
    public synchronized void append(List<UsageRecord> records) {
        if (records.isEmpty()) return;
        List<String> lines = new ArrayList<>(records.size());
        for (UsageRecord r : records) lines.add(r.toJson());
        long now = clock.getAsLong();
        Path file = null;
        if (dir != null) {
            try {
                file = dir.resolve("b-" + now + "-" + (counter++) + "-" + lines.size() + ".jsonl");
                write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                file = null;
            }
        }
        if (file == null) memory.add(new Batch(now, lines, null));
        if (dir != null) {
            try {
                write(dir.resolve("seq"), (epoch + " " + seq).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // disque en erreur : la suite continue en mémoire
            }
        }
        trim(now);
    }

    /** Lots en attente, du plus ancien au plus récent. */
    public synchronized List<Batch> pending() {
        List<Batch> out = new ArrayList<>(memory);
        if (dir != null) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "b-*.jsonl")) {
                for (Path p : s) if (NAME.matcher(p.getFileName().toString()).matches()) files.add(p);
            } catch (IOException e) {
                return out;
            }
            for (Path p : files) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                m.matches();
                try {
                    List<String> lines = new ArrayList<>();
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isBlank()) lines.add(l);
                    out.add(new Batch(Long.parseLong(m.group(1)), lines, p));
                } catch (NoSuchFileException e) {
                    // envoyé entre-temps
                } catch (IOException e) {
                    // illisible : laissé là, retiré par l'âge
                }
            }
        }
        out.sort((a, b) -> a.at() != b.at() ? Long.compare(a.at(), b.at()) : Long.compare(seqOf(a.file()), seqOf(b.file())));
        return out;
    }

    /** Nombre d'enregistrements en attente. */
    public synchronized int size() {
        int n = 0;
        for (Batch b : pending()) n += b.lines().size();
        return n;
    }

    /** Le lot a été acquitté par le calorimètre. */
    public synchronized void remove(Batch b) {
        memory.remove(b);
        if (b.file() != null) {
            try {
                Files.deleteIfExists(b.file());
            } catch (IOException e) {
                // supprimé au prochain passage (un renvoi est sans effet : idempotence par id)
            }
        }
    }

    /** Abandonne un lot refusé en bloc (400 : il ne passera jamais) en le comptant. */
    public synchronized void drop(Batch b) {
        addDropped(b.lines().size());
        remove(b);
    }

    /** Applique les bornes : âge, puis nombre d'enregistrements, en retirant les plus anciens. */
    public synchronized void trim(long now) {
        List<Batch> all = pending();
        int total = 0;
        for (Batch b : all) total += b.lines().size();
        for (Batch b : all) {
            if (now - b.at() <= maxAgeMs && total <= maxRecords) break;
            total -= b.lines().size();
            drop(b);
        }
    }

    private void addDropped(long n) {
        long v = dropped.addAndGet(n);
        if (dir != null) {
            try {
                write(dir.resolve("dropped"), Long.toString(v).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // compté en mémoire
            }
        }
    }

    private static long seqOf(Path p) {
        if (p == null) return -1;
        Matcher m = NAME.matcher(p.getFileName().toString());
        return m.matches() ? Long.parseLong(m.group(2)) : -1;
    }

    private void write(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(dir, ".w-", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

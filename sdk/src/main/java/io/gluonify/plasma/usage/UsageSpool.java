package io.gluonify.plasma.usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable queue of unacknowledged usage batches ({@code metering.md} § 4.2), on the emitter's LOCAL disk (never on {@code /distributed/*}):
 * one {@code b-<ms>-<n>-<count>.jsonl} file per batch (one JSON line per record), written to a temporary file then moved (a reader never sees a half-written batch).
 * Bounded: beyond {@code maxRecords} records or {@code maxAgeMs}, the oldest batches are removed and COUNTED ({@link #dropped()}, persisted):
 * the loss is in the customer's favour, and visible to the calorimeter through the {@code seq} gap.
 * <p>Also keeps the emitter's {@code seq} sequence ({@code seq} file: {@code <epoch> <last>}, rewritten at each batch): it survives a restart without a gap.
 * Without a directory ({@code dir == null}), everything stays in memory (lost at restart, same bounds).
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
    private long epoch;
    private long seq;

    /** A pending batch: its JSON lines, its write date, and its file (null in memory). */
    public record Batch(long at, List<String> lines, Path file) {}

    public UsageSpool(final Path dir, final int maxRecords, final long maxAgeMs, final LongSupplier clock) {
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
                epoch = now; // unreadable sequence: a new epoch (the calorimeter does not look for a gap between two epochs)
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

    /** Records dropped since the beginning (persisted), exposed as {@code gluonify_usage_dropped_total}. */
    public long dropped() { return dropped.get(); }

    /** Appends a batch (never an exception: a failing disk keeps the batch in memory). */
    public synchronized void append(final List<UsageRecord> records) {
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
                // failing disk: the sequence continues in memory
            }
        }
        trim(now);
    }

    /** Pending batches, from the oldest to the newest. */
    public synchronized List<Batch> pending() {
        List<Batch> out = new ArrayList<>(memory);
        if (dir != null) {
            final List<Path> files = batchFiles();
            for (Path p : files) {
                final Batch b = readBatch(p);
                if (b != null) out.add(b);
            }
        }
        out.sort((a, b) -> a.at() != b.at() ? Long.compare(a.at(), b.at()) : Long.compare(seqOf(a.file()), seqOf(b.file())));
        return out;
    }

    /** The batch files of the directory, or an empty list if it cannot be listed. */
    private List<Path> batchFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "b-*.jsonl")) {
            for (Path p : s) if (NAME.matcher(p.getFileName().toString()).matches()) files.add(p);
        } catch (IOException e) {
            return List.of();
        }
        return files;
    }

    /** The batch of a file, or {@code null} if it was sent in the meantime ({@code NoSuchFileException}) or is unreadable: left there, removed by age. */
    private static Batch readBatch(final Path p) {
        Matcher m = NAME.matcher(p.getFileName().toString());
        m.matches();
        try {
            List<String> lines = new ArrayList<>();
            for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isBlank()) lines.add(l);
            return new Batch(Long.parseLong(m.group(1)), lines, p);
        } catch (IOException e) {
            return null;
        }
    }

    /** Number of pending records. */
    public synchronized int size() {
        int n = 0;
        for (Batch b : pending()) n += b.lines().size();
        return n;
    }

    /** The batch has been acknowledged by the calorimeter. */
    public synchronized void remove(final Batch b) {
        memory.remove(b);
        if (b.file() != null) {
            try {
                Files.deleteIfExists(b.file());
            } catch (IOException e) {
                // deleted on the next pass (a resend has no effect: idempotence by id)
            }
        }
    }

    /** Drops a batch refused as a whole (400: it will never go through), counting it. */
    public synchronized void drop(final Batch b) {
        addDropped(b.lines().size());
        remove(b);
    }

    /** Applies the bounds: age, then number of records, removing the oldest. */
    public synchronized void trim(final long now) {
        List<Batch> all = pending();
        int total = 0;
        for (Batch b : all) total += b.lines().size();
        for (Batch b : all) {
            if (now - b.at() <= maxAgeMs && total <= maxRecords) break;
            total -= b.lines().size();
            drop(b);
        }
    }

    private void addDropped(final long n) {
        long v = dropped.addAndGet(n);
        if (dir != null) {
            try {
                write(dir.resolve("dropped"), Long.toString(v).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // counted in memory
            }
        }
    }

    private static long seqOf(final Path p) {
        if (p == null) return -1;
        Matcher m = NAME.matcher(p.getFileName().toString());
        return m.matches() ? Long.parseLong(m.group(2)) : -1;
    }

    private void write(final Path target, final byte[] data) throws IOException {
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

package io.gluonify.plasma.services.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Private files of {@code ~/.gluonify}: folders 0700, files 0600, atomic write (temporary file then rename). Not a public API. */
public final class SecureFiles {
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");

    private SecureFiles() {
    }

    static boolean posix(final Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Creates the folder (and its parents) if missing; the folder itself is 0700. */
    public static void directory(final Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            if (posix(dir)) {
                Files.setPosixFilePermissions(dir, DIR);
            }
            return;
        }
        final Path parent = dir.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            if (posix(dir)) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR));
            }
            else {
                Files.createDirectory(dir);
            }
        }
        catch (final FileAlreadyExistsException e) {
            // created in the meantime by another process
        }
    }

    /** Writes {@code content} to {@code file} (0600), atomically: a reader sees the old content or the new one, never a half-written file. */
    public static void write(final Path file, final String content) throws IOException {
        final Path dir = file.toAbsolutePath().getParent();
        directory(dir);
        final FileAttribute<?>[] attrs = posix(dir) ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)} : new FileAttribute<?>[0];
        final Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp", attrs);
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix(file)) {
                Files.setPosixFilePermissions(file, FILE);
            }
        }
        finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The content of the file, or {@code null} if it does not exist. */
    public static String read(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}

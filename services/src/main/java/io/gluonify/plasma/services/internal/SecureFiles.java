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

/** Fichiers privés de {@code ~/.gluonify} : dossiers 0700, fichiers 0600, écriture atomique (fichier temporaire puis renommage). Pas une API publique. */
public final class SecureFiles {
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");

    private SecureFiles() {
    }

    static boolean posix(final Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Crée le dossier (et ses parents) s'il manque ; le dossier lui-même est 0700. */
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
            // créé entre-temps par un autre processus
        }
    }

    /** Écrit {@code content} dans {@code file} (0600), atomiquement : un lecteur voit l'ancien contenu ou le nouveau, jamais un fichier à moitié écrit. */
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

    /** Le contenu du fichier, ou {@code null} s'il n'existe pas. */
    public static String read(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}

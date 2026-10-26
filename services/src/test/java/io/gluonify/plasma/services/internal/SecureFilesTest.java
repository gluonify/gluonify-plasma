package io.gluonify.plasma.services.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The private files of the Gluonify folder: owner-only permissions, atomic write, reading what may be absent. */
class SecureFilesTest {
    @TempDir
    Path dir;

    @Test
    void aFolderIsCreatedWithItsParentsAndOwnerOnlyAccess() throws Exception {
        final Path d = dir.resolve("a/b/c");
        SecureFiles.directory(d);
        assertTrue(Files.isDirectory(d));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(d));
    }

    @Test
    void anExistingFolderIsTightenedToOwnerOnlyAccess() throws Exception {
        final Path d = Files.createDirectory(dir.resolve("open"));
        Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwxr-xr-x"));
        SecureFiles.directory(d);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(d));
    }

    @Test
    void aFileIsWrittenOwnerOnlyAndReplacedWholeWithNoTemporaryLeftBehind() throws Exception {
        final Path f = dir.resolve("sub/tokens.json");
        SecureFiles.write(f, "one");
        assertEquals("one", SecureFiles.read(f));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw-rw-"));
        SecureFiles.write(f, "two é");
        assertEquals("two é", SecureFiles.read(f));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f), "rewritten files are tightened again");
        try (var names = Files.list(f.getParent())) {
            assertEquals(1, names.count(), "only the file itself remains");
        }
    }

    @Test
    void anAbsentFileOrADirectoryReadsAsNull() throws Exception {
        assertNull(SecureFiles.read(dir.resolve("absent")));
        assertNull(SecureFiles.read(dir));
    }
}

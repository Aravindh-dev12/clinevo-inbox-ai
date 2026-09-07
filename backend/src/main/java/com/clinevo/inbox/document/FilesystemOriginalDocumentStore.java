package com.clinevo.inbox.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

@Component
public class FilesystemOriginalDocumentStore implements OriginalDocumentStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ
    );

    private final boolean filesystemMode;
    private final Path root;

    public FilesystemOriginalDocumentStore(
            @Value("${clinevo.document-storage.mode:database}") String mode,
            @Value("${clinevo.document-storage.root:/var/lib/clinevo/documents}") String root
    ) {
        this.filesystemMode = "filesystem".equalsIgnoreCase(mode);
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject storeOriginal(String sha256, byte[] content) {
        if (!filesystemMode) return new StoredObject("DATABASE", null);
        return store("originals", sha256, content);
    }

    @Override
    public StoredObject storeQuarantine(String sha256, byte[] content) {
        if (!filesystemMode) return new StoredObject("DATABASE_QUARANTINE", null);
        return store("quarantine", sha256, content);
    }

    @Override
    public byte[] load(String storageKey) {
        if (!filesystemMode) throw new IllegalStateException("External document storage is disabled");
        try {
            Path target = resolve(storageKey);
            if (!Files.isRegularFile(target)) throw new IllegalStateException("Stored document is missing");
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load stored document", ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (!filesystemMode || storageKey == null || storageKey.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to delete stored document", ex);
        }
    }

    @Override
    public boolean external() {
        return filesystemMode;
    }

    private StoredObject store(String namespace, String sha256, byte[] content) {
        validateSha(sha256);
        if (content == null || content.length == 0) throw new IllegalArgumentException("Cannot store empty document");
        if (!sha256.equals(hash(content))) throw new IllegalArgumentException("Document content does not match SHA-256 key");

        String key = namespace + "/" + sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256 + ".bin";
        Path target = resolve(key);
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            hardenDirectory(parent);
            if (!Files.exists(target)) {
                Path temp = Files.createTempFile(parent, ".incoming-", ".tmp");
                try {
                    Files.write(temp, content);
                    hardenFile(temp);
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temp, target);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            if (Files.size(target) != content.length) {
                throw new IllegalStateException("Existing content-addressed object has an unexpected size");
            }
            hardenFile(target);
            return new StoredObject("FILESYSTEM", key);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist immutable document", ex);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Storage key is required");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return resolved;
    }

    private void hardenDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/non-POSIX filesystems still rely on the runtime account and mount permissions.
        }
    }

    private void hardenFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/non-POSIX filesystems still rely on the runtime account and mount permissions.
        }
    }

    private static void validateSha(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 key");
        }
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

package com.clinevo.inbox.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemOriginalDocumentStoreTest {
    @TempDir Path tempDir;

    @Test
    void storesAndLoadsImmutableContentAddressedOriginal() throws Exception {
        byte[] content = "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        FilesystemOriginalDocumentStore store = new FilesystemOriginalDocumentStore("filesystem", tempDir.toString());

        OriginalDocumentStore.StoredObject first = store.storeOriginal(sha, content);
        OriginalDocumentStore.StoredObject second = store.storeOriginal(sha, content);

        assertThat(first.provider()).isEqualTo("FILESYSTEM");
        assertThat(first.key()).isEqualTo(second.key()).startsWith("originals/");
        assertThat(store.load(first.key())).containsExactly(content);
    }

    @Test
    void refusesContentThatDoesNotMatchDigest() {
        FilesystemOriginalDocumentStore store = new FilesystemOriginalDocumentStore("filesystem", tempDir.toString());
        assertThatThrownBy(() -> store.storeOriginal("0".repeat(64), "tampered".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsTraversalStorageKey() {
        FilesystemOriginalDocumentStore store = new FilesystemOriginalDocumentStore("filesystem", tempDir.toString());
        assertThatThrownBy(() -> store.load("../../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid storage key");
    }
}

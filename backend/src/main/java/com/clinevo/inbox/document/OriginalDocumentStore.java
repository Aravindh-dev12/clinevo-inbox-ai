package com.clinevo.inbox.document;

public interface OriginalDocumentStore {
    StoredObject storeOriginal(String sha256, byte[] content);
    StoredObject storeQuarantine(String sha256, byte[] content);
    byte[] load(String storageKey);
    void delete(String storageKey);
    boolean external();

    record StoredObject(String provider, String key) {}
}

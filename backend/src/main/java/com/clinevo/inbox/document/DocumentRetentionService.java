package com.clinevo.inbox.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class DocumentRetentionService {
    private final JdbcTemplate jdbc;
    private final OriginalDocumentStore store;
    private final long retentionDays;
    private final int batchSize;

    public DocumentRetentionService(
            JdbcTemplate jdbc,
            OriginalDocumentStore store,
            @Value("${clinevo.document-storage.retention-days:0}") long retentionDays,
            @Value("${clinevo.document-storage.purge-batch-size:50}") int batchSize
    ) {
        this.jdbc = jdbc;
        this.store = store;
        this.retentionDays = Math.max(0, retentionDays);
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    public long retentionDays() {
        return retentionDays;
    }

    public Instant retentionUntil(Instant from) {
        return retentionDays == 0 ? null : from.plusSeconds(Math.multiplyExact(retentionDays, 86_400L));
    }

    @Scheduled(fixedDelayString = "${clinevo.document-storage.purge-poll-ms:3600000}")
    public void purgeExpired() {
        if (retentionDays == 0) return;
        Instant now = Instant.now();
        String sql = """
                SELECT ID, MESSAGE_ID, SHA256, STORAGE_KEY
                FROM ATTACHMENT
                WHERE RETENTION_UNTIL IS NOT NULL AND RETENTION_UNTIL <= ? AND PURGED_AT IS NULL
                  AND PROCESSING_STATUS IN ('PROCESSED','QUARANTINED_MALWARE')
                ORDER BY RETENTION_UNTIL
                FETCH FIRST %d ROWS ONLY
                """.formatted(batchSize);
        List<PurgeCandidate> candidates = jdbc.query(sql, (rs, rowNum) -> new PurgeCandidate(
                rs.getLong("ID"), rs.getLong("MESSAGE_ID"), rs.getString("SHA256"), rs.getString("STORAGE_KEY")), now);

        for (PurgeCandidate candidate : candidates) {
            try {
                if (candidate.storageKey() != null) store.delete(candidate.storageKey());
                int updated = jdbc.update("""
                        UPDATE ATTACHMENT
                        SET CONTENT_BLOB=NULL, STORAGE_KEY=NULL, PURGED_AT=?
                        WHERE ID=? AND PURGED_AT IS NULL
                        """, now, candidate.id());
                if (updated == 1) {
                    audit(candidate.messageId(), candidate.id(), candidate.sha256(), now);
                }
            } catch (RuntimeException ignored) {
                // Leave metadata untouched so the next scheduled pass can retry the purge.
            }
        }
    }

    private void audit(long messageId, long attachmentId, String sha256, Instant now) {
        String digest = sha256 == null ? "unknown" : sha256.substring(0, Math.min(16, sha256.length()));
        String details = "{\"detail\":\"attachmentId=" + attachmentId + ", sha256Prefix=" + digest + "\"}";
        jdbc.update("""
                INSERT INTO AUDIT_EVENT
                  (ID, MESSAGE_ID, EVENT_TYPE, ACTOR_TYPE, ACTOR_ID, DETAILS_JSON, CREATED_AT)
                VALUES
                  (AUDIT_EVENT_SEQ.NEXTVAL, ?, 'ATTACHMENT_CONTENT_PURGED', 'SYSTEM', 'clinevo-inbox-api', ?, ?)
                """, messageId, details, now);
    }

    private record PurgeCandidate(long id, long messageId, String sha256, String storageKey) {}
}

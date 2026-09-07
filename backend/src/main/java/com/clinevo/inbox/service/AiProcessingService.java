package com.clinevo.inbox.service;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.mail.MailAttachment;
import com.clinevo.inbox.repository.InboxMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class AiProcessingService {
    private static final Set<String> VALID_CATEGORIES = Set.of("ICSR", "PQC", "MI", "NOT_RELEVANT");

    private final InboxMessageRepository messages;
    private final JdbcTemplate jdbc;
    private final RestClient restClient;
    private final Executor executor;
    private final int maxAttempts;
    private final int jobBatchSize;
    private final long retryBaseSeconds;
    private final long jobLeaseSeconds;

    public AiProcessingService(
            InboxMessageRepository messages,
            JdbcTemplate jdbc,
            RestClient.Builder restClientBuilder,
            @Qualifier("aiExecutor") Executor executor,
            @Value("${clinevo.ai-service-url}") String aiServiceUrl,
            @Value("${clinevo.job-max-attempts:3}") int maxAttempts,
            @Value("${clinevo.job-batch-size:4}") int jobBatchSize,
            @Value("${clinevo.job-retry-base-seconds:5}") long retryBaseSeconds,
            @Value("${clinevo.job-lease-seconds:300}") long jobLeaseSeconds
    ) {
        this.messages = messages;
        this.jdbc = jdbc;
        this.restClient = restClientBuilder.baseUrl(aiServiceUrl).build();
        this.executor = executor;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.jobBatchSize = Math.max(1, Math.min(jobBatchSize, 20));
        this.retryBaseSeconds = Math.max(1, retryBaseSeconds);
        this.jobLeaseSeconds = Math.max(30, jobLeaseSeconds);
    }

    @Transactional
    public void enqueue(long messageId, List<MailAttachment> attachments) {
        if (hasActiveOrSuccessfulJob(messageId)) return;
        Long anyJobs = jdbc.queryForObject("SELECT COUNT(*) FROM PROCESSING_JOB WHERE MESSAGE_ID = ?", Long.class, messageId);
        if (anyJobs != null && anyJobs == 0) {
            jdbc.update("DELETE FROM ATTACHMENT WHERE MESSAGE_ID = ?", messageId);
            for (MailAttachment attachment : attachments) stageAttachment(messageId, attachment);
        }
        insertJob(messageId);
        InboxMessage message = messages.findById(messageId).orElseThrow();
        message.setStatus("QUEUED");
        messages.save(message);
        audit(messageId, "PROCESSING_JOB_QUEUED", "SYSTEM", "durable job created");
    }

    @Transactional
    public void requeueExisting(long messageId) {
        if (hasActiveJob(messageId)) return;
        insertJob(messageId);
        InboxMessage message = messages.findById(messageId).orElseThrow();
        message.setStatus("QUEUED");
        messages.save(message);
        audit(messageId, "PROCESSING_JOB_REQUEUED", "USER", "manual durable requeue");
    }

    public boolean hasActiveOrSuccessfulJob(long messageId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM PROCESSING_JOB WHERE MESSAGE_ID = ? AND STATUS IN ('QUEUED','PROCESSING','SUCCEEDED')", Long.class, messageId);
        return count != null && count > 0;
    }

    public boolean hasActiveJob(long messageId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM PROCESSING_JOB WHERE MESSAGE_ID = ? AND STATUS IN ('QUEUED','PROCESSING')", Long.class, messageId);
        return count != null && count > 0;
    }

    @Scheduled(fixedDelayString = "${clinevo.job-poll-ms:1000}")
    public void pollJobs() {
        Instant now = Instant.now();
        String sql = "SELECT ID FROM PROCESSING_JOB WHERE STATUS = 'QUEUED' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPT_COUNT < MAX_ATTEMPTS ORDER BY CREATED_AT FETCH FIRST " + jobBatchSize + " ROWS ONLY";
        List<Long> ids = jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), now);
        for (Long id : ids) {
            int claimed = jdbc.update("""
                    UPDATE PROCESSING_JOB
                    SET STATUS='PROCESSING', LOCKED_AT=?, ATTEMPT_COUNT=ATTEMPT_COUNT+1, UPDATED_AT=?
                    WHERE ID=? AND STATUS='QUEUED' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPT_COUNT < MAX_ATTEMPTS
                    """, now, now, id, now);
            if (claimed == 1) executor.execute(() -> processClaimedJob(id));
        }
    }

    @Scheduled(fixedDelayString = "${clinevo.job-recovery-ms:30000}")
    public void recoverStaleJobs() {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(jobLeaseSeconds);
        List<Long> terminalMessages = jdbc.query(
                "SELECT MESSAGE_ID FROM PROCESSING_JOB WHERE STATUS='PROCESSING' AND LOCKED_AT < ? AND ATTEMPT_COUNT >= MAX_ATTEMPTS",
                (rs, rowNum) -> rs.getLong(1), cutoff);
        jdbc.update("""
                UPDATE PROCESSING_JOB SET STATUS='FAILED', LAST_ERROR='worker lease expired', LOCKED_AT=NULL, UPDATED_AT=?
                WHERE STATUS='PROCESSING' AND LOCKED_AT < ? AND ATTEMPT_COUNT >= MAX_ATTEMPTS
                """, now, cutoff);
        for (Long messageId : terminalMessages) {
            messages.findById(messageId).ifPresent(message -> {
                message.setStatus("PROCESSING_FAILED");
                messages.save(message);
            });
            audit(messageId, "PROCESSING_JOB_LEASE_EXHAUSTED", "SYSTEM", "worker lease expired at max attempts");
        }
        jdbc.update("""
                UPDATE PROCESSING_JOB SET STATUS='QUEUED', NEXT_ATTEMPT_AT=?, LOCKED_AT=NULL,
                    LAST_ERROR='worker lease expired; recovered for retry', UPDATED_AT=?
                WHERE STATUS='PROCESSING' AND LOCKED_AT < ? AND ATTEMPT_COUNT < MAX_ATTEMPTS
                """, now, now, cutoff);
    }

    private void processClaimedJob(long jobId) {
        JobState job = jobState(jobId);
        long started = System.nanoTime();
        try {
            processMessage(job.messageId());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            jdbc.update("UPDATE PROCESSING_JOB SET STATUS='SUCCEEDED', LOCKED_AT=NULL, LAST_ERROR=NULL, UPDATED_AT=? WHERE ID=?", Instant.now(), jobId);
            InboxMessage message = messages.findById(job.messageId()).orElseThrow();
            message.setProcessingMs(elapsedMs);
            message.setStatus("READY_FOR_REVIEW");
            messages.save(message);
            audit(job.messageId(), "AI_PROCESSING_SUCCEEDED", "SYSTEM", "jobId=" + jobId + ", processingMs=" + elapsedMs);
        } catch (Exception ex) {
            clearAiResults(job.messageId());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            handleFailure(jobId, elapsedMs, ex);
        }
    }

    private void processMessage(long messageId) {
        InboxMessage message = messages.findById(messageId).orElseThrow();
        message.setStatus("PROCESSING");
        messages.save(message);
        clearAiResults(messageId);

        Map<String, ClassificationCandidate> classifications = new HashMap<>();
        List<String> summaries = new ArrayList<>();
        String emailText = message.getBodyText() == null ? "" : message.getBodyText();
        if (!emailText.isBlank()) {
            JsonNode emailResult = callTextAiService(messageId, emailText);
            collectClassifications(classifications, emailResult);
            persistFacts(messageId, null, emailResult);
            String summary = emailResult.path("summary").asText("");
            if (!summary.isBlank()) summaries.add("Email: " + summary);
        }

        for (StoredAttachment attachment : loadAttachments(messageId)) {
            if (!attachment.shouldProcess()) continue;
            try {
                JsonNode result = callPdfAiService(emailText, attachment);
                updateAttachmentResult(attachment.id(), result);
                collectClassifications(classifications, result);
                persistFacts(messageId, attachment.id(), result);
                String summary = result.path("summary").asText("");
                if (!summary.isBlank()) summaries.add(attachment.fileName() + ": " + summary);
            } catch (Exception ex) {
                jdbc.update("UPDATE ATTACHMENT SET PROCESSING_STATUS='PROCESSING_FAILED' WHERE ID=?", attachment.id());
                throw ex;
            }
        }

        if (classifications.isEmpty()) {
            classifications.put("NOT_RELEVANT", new ClassificationCandidate("NOT_RELEVANT", 0.90, "No supported safety, quality or medical-information content was extracted."));
        }
        if (classifications.keySet().stream().anyMatch(category -> !"NOT_RELEVANT".equals(category))) {
            classifications.remove("NOT_RELEVANT");
        }
        persistClassifications(messageId, classifications.values());
        message.setAiSummary(String.join("\n\n", summaries));
        messages.save(message);
    }

    private JsonNode callTextAiService(long messageId, String emailText) {
        JsonNode result = restClient.post()
                .uri("/process-text")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("source_name", "email:" + messageId, "text", emailText))
                .retrieve()
                .body(JsonNode.class);
        if (result == null) throw new IllegalStateException("AI text service returned an empty response");
        return result;
    }

    private JsonNode callPdfAiService(String emailText, StoredAttachment attachment) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("email_text", emailText);
        body.part("file", new ByteArrayResource(attachment.bytes()) {
            @Override public String getFilename() { return attachment.fileName(); }
        }).contentType(MediaType.APPLICATION_PDF);
        JsonNode result = restClient.post()
                .uri("/process")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(JsonNode.class);
        if (result == null) throw new IllegalStateException("AI PDF service returned an empty response");
        return result;
    }

    private List<StoredAttachment> loadAttachments(long messageId) {
        return jdbc.query("""
                SELECT ID, FILE_NAME, MIME_TYPE, CONTENT_BLOB, PROCESSING_STATUS
                FROM ATTACHMENT WHERE MESSAGE_ID = ? ORDER BY ID
                """, (rs, rowNum) -> new StoredAttachment(
                rs.getLong("ID"), rs.getString("FILE_NAME"), rs.getString("MIME_TYPE"),
                rs.getBytes("CONTENT_BLOB"), rs.getString("PROCESSING_STATUS")), messageId);
    }

    private void stageAttachment(long messageId, MailAttachment attachment) {
        String fileName = safeFileName(attachment.fileName());
        String mimeType = attachment.contentType() == null ? "application/octet-stream" : attachment.contentType();
        byte[] bytes = attachment.bytes() == null ? new byte[0] : attachment.bytes();
        String status;
        String rejectionReason = attachment.rejectionReason();
        if (attachment.rejected()) {
            status = "REJECTED_TOO_LARGE";
        } else if (hasPdfSignature(bytes)) {
            status = "STAGED";
            mimeType = "application/pdf";
        } else if (attachment.isPdf()) {
            status = "REJECTED_INVALID_PDF";
            rejectionReason = "file was labeled as PDF but did not contain a PDF signature";
        } else {
            status = "LOGGED_UNSUPPORTED";
        }
        String sha = bytes.length == 0 ? null : sha256(bytes);
        jdbc.update("""
                INSERT INTO ATTACHMENT
                  (ID, MESSAGE_ID, FILE_NAME, MIME_TYPE, SHA256, ORIGINAL_SIZE, CONTENT_BLOB,
                   REJECTION_REASON, PROCESSING_STATUS, CREATED_AT)
                VALUES
                  (ATTACHMENT_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, messageId, fileName, mimeType, sha, attachment.originalSize(), bytes.length == 0 ? null : bytes,
                rejectionReason, status, Instant.now());
        if (status.startsWith("REJECTED")) audit(messageId, "ATTACHMENT_REJECTED", "SYSTEM", fileName + ": " + rejectionReason);
    }

    private void updateAttachmentResult(long attachmentId, JsonNode result) {
        jdbc.update("""
                UPDATE ATTACHMENT SET PDF_TYPE=?, DETECTED_LANGUAGE=?, OCR_CONFIDENCE=?, PROCESSING_MS=?,
                    PROCESSING_STATUS='PROCESSED', REJECTION_REASON=NULL
                WHERE ID=?
                """,
                result.path("pdf_type").asText("UNKNOWN"),
                result.path("detected_language").asText("unknown"),
                result.path("ocr_confidence").isNumber() ? result.path("ocr_confidence").asDouble() : null,
                result.path("processing_ms").asLong(0), attachmentId);
    }

    private void collectClassifications(Map<String, ClassificationCandidate> target, JsonNode result) {
        for (JsonNode item : result.path("classifications")) {
            String category = item.path("category").asText("").toUpperCase();
            if (!VALID_CATEGORIES.contains(category)) continue;
            ClassificationCandidate candidate = new ClassificationCandidate(
                    category, item.path("confidence").asDouble(0), limit(item.path("reason").asText(""), 1900));
            target.merge(category, candidate, (left, right) -> right.confidence() > left.confidence() ? right : left);
        }
    }

    private void persistClassifications(long messageId, Iterable<ClassificationCandidate> candidates) {
        for (ClassificationCandidate item : candidates) {
            jdbc.update("""
                    INSERT INTO CLASSIFICATION
                      (ID, MESSAGE_ID, CATEGORY, CONFIDENCE, REASON, MODEL_NAME, CREATED_AT)
                    VALUES
                      (CLASSIFICATION_SEQ.NEXTVAL, ?, ?, ?, ?, 'durable-pipeline', ?)
                    """, messageId, item.category(), item.confidence(), item.reason(), Instant.now());
        }
    }

    private void persistFacts(long messageId, Long attachmentId, JsonNode result) {
        Iterator<Map.Entry<String, JsonNode>> groups = result.path("extracted_facts").fields();
        while (groups.hasNext()) {
            Map.Entry<String, JsonNode> group = groups.next();
            Iterator<Map.Entry<String, JsonNode>> fields = group.getValue().fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode valueNode = field.getValue();
                JsonNode source = valueNode.path("source");
                String value = valueNode.path("value").asText("Not stated");
                if (source.isMissingNode() || source.isNull() || "Not stated".equalsIgnoreCase(value)) continue;
                String sourceType = source.path("source_type").asText(attachmentId == null ? "EMAIL" : "PDF").toUpperCase();
                if (!Set.of("EMAIL", "PDF").contains(sourceType)) continue;
                Long linkedAttachment = "PDF".equals(sourceType) ? attachmentId : null;
                jdbc.update("""
                        INSERT INTO EXTRACTED_FACT
                          (ID, MESSAGE_ID, ATTACHMENT_ID, FACT_GROUP, FIELD_NAME, FIELD_VALUE, CONFIDENCE,
                           SOURCE_TYPE, SOURCE_NAME, SOURCE_PAGE, EVIDENCE_TEXT, CREATED_AT)
                        VALUES
                          (EXTRACTED_FACT_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        messageId, linkedAttachment, group.getKey(), field.getKey(), value,
                        valueNode.path("confidence").asDouble(0), sourceType,
                        limit(source.path("source_name").asText(sourceType.equals("EMAIL") ? "email:" + messageId : "unknown"), 950),
                        source.path("page").isInt() ? source.path("page").asInt() : null,
                        limit(source.path("evidence").asText(null), 3900), Instant.now());
            }
        }
    }

    private void clearAiResults(long messageId) {
        jdbc.update("DELETE FROM EXTRACTED_FACT WHERE MESSAGE_ID = ?", messageId);
        jdbc.update("DELETE FROM CLASSIFICATION WHERE MESSAGE_ID = ?", messageId);
    }

    void handleFailure(long jobId, long elapsedMs, Exception ex) {
        JobState job = jobState(jobId);
        InboxMessage message = messages.findById(job.messageId()).orElseThrow();
        message.setProcessingMs(elapsedMs);
        String error = limit(ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "processing error" : ex.getMessage()), 1900);
        Instant now = Instant.now();
        if (job.attemptCount() < job.maxAttempts()) {
            long exponent = Math.max(0, Math.min(10, job.attemptCount() - 1));
            long delay = Math.min(300, retryBaseSeconds * (1L << exponent));
            jdbc.update("""
                    UPDATE PROCESSING_JOB SET STATUS='QUEUED', NEXT_ATTEMPT_AT=?, LOCKED_AT=NULL,
                        LAST_ERROR=?, UPDATED_AT=? WHERE ID=?
                    """, now.plusSeconds(delay), error, now, jobId);
            message.setStatus("RETRY_QUEUED");
            audit(job.messageId(), "AI_PROCESSING_RETRY", "SYSTEM", "jobId=" + jobId + ", retryInSeconds=" + delay + ", error=" + error);
        } else {
            jdbc.update("UPDATE PROCESSING_JOB SET STATUS='FAILED', LOCKED_AT=NULL, LAST_ERROR=?, UPDATED_AT=? WHERE ID=?", error, now, jobId);
            message.setStatus("PROCESSING_FAILED");
            audit(job.messageId(), "AI_PROCESSING_FAILED", "SYSTEM", "jobId=" + jobId + ", error=" + error);
        }
        messages.save(message);
    }

    private JobState jobState(long jobId) {
        return jdbc.queryForObject("SELECT MESSAGE_ID, ATTEMPT_COUNT, MAX_ATTEMPTS FROM PROCESSING_JOB WHERE ID=?",
                (rs, rowNum) -> new JobState(rs.getLong(1), rs.getInt(2), rs.getInt(3)), jobId);
    }

    private void insertJob(long messageId) {
        jdbc.update("""
                INSERT INTO PROCESSING_JOB
                  (ID, MESSAGE_ID, STATUS, ATTEMPT_COUNT, MAX_ATTEMPTS, NEXT_ATTEMPT_AT, CREATED_AT, UPDATED_AT)
                VALUES
                  (PROCESSING_JOB_SEQ.NEXTVAL, ?, 'QUEUED', 0, ?, ?, ?, ?)
                """, messageId, maxAttempts, Instant.now(), Instant.now(), Instant.now());
    }

    private void audit(long messageId, String type, String actorType, String detail) {
        jdbc.update("""
                INSERT INTO AUDIT_EVENT
                  (ID, MESSAGE_ID, EVENT_TYPE, ACTOR_TYPE, ACTOR_ID, DETAILS_JSON, CREATED_AT)
                VALUES
                  (AUDIT_EVENT_SEQ.NEXTVAL, ?, ?, ?, 'clinevo-inbox-api', ?, ?)
                """, messageId, type, actorType, "{\"detail\":\"" + jsonEscape(limit(detail, 3000)) + "\"}", Instant.now());
    }

    private boolean hasPdfSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "attachment";
        String normalized = raw.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\u0000]", "_");
        return limit(normalized, 950);
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record StoredAttachment(long id, String fileName, String contentType, byte[] bytes, String status) {
        boolean shouldProcess() {
            return bytes != null && bytes.length >= 5 && Set.of("STAGED", "PROCESSED", "PROCESSING_FAILED").contains(status);
        }
    }

    private record ClassificationCandidate(String category, double confidence, String reason) {}
    private record JobState(long messageId, int attemptCount, int maxAttempts) {}
}

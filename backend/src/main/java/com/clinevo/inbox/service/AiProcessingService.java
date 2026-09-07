package com.clinevo.inbox.service;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.mail.MailAttachment;
import com.clinevo.inbox.repository.InboxMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
public class AiProcessingService {
    private final InboxMessageRepository messages;
    private final JdbcTemplate jdbc;
    private final RestClient restClient;

    public AiProcessingService(
            InboxMessageRepository messages,
            JdbcTemplate jdbc,
            RestClient.Builder restClientBuilder,
            @Value("${clinevo.ai-service-url}") String aiServiceUrl
    ) {
        this.messages = messages;
        this.jdbc = jdbc;
        this.restClient = restClientBuilder.baseUrl(aiServiceUrl).build();
    }

    @Async
    public void processAsync(long messageId, String emailText, List<MailAttachment> attachments) {
        InboxMessage message = messages.findById(messageId).orElseThrow();
        message.setStatus("PROCESSING");
        messages.save(message);

        long started = System.nanoTime();
        StringBuilder summaries = new StringBuilder();
        try {
            for (MailAttachment attachment : attachments) {
                if (!attachment.isPdf()) {
                    persistUnsupportedAttachment(messageId, attachment);
                    continue;
                }
                JsonNode result = callAiService(emailText, attachment);
                persistAttachmentResult(messageId, attachment, result);
                persistClassifications(messageId, result);
                persistFacts(messageId, result);
                String summary = result.path("summary").asText("");
                if (!summary.isBlank()) {
                    if (!summaries.isEmpty()) summaries.append("\n\n");
                    summaries.append(attachment.fileName()).append(": ").append(summary);
                }
            }
            message.setAiSummary(summaries.toString());
            message.setStatus("READY_FOR_REVIEW");
        } catch (Exception ex) {
            message.setStatus("PROCESSING_FAILED");
            audit(messageId, "AI_PROCESSING_FAILED", "SYSTEM", ex.getClass().getSimpleName());
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            message.setProcessingMs(elapsedMs);
            messages.save(message);
            audit(messageId, "AI_PROCESSING_FINISHED", "SYSTEM", "processingMs=" + elapsedMs);
        }
    }

    private JsonNode callAiService(String emailText, MailAttachment attachment) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("email_text", emailText == null ? "" : emailText);
        body.part("file", new ByteArrayResource(attachment.bytes()) {
            @Override public String getFilename() { return attachment.fileName(); }
        }).contentType(MediaType.APPLICATION_PDF);

        return restClient.post()
                .uri("/process")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(JsonNode.class);
    }

    private void persistAttachmentResult(long messageId, MailAttachment attachment, JsonNode result) throws Exception {
        jdbc.update("""
                INSERT INTO ATTACHMENT
                  (ID, MESSAGE_ID, FILE_NAME, MIME_TYPE, SHA256, PDF_TYPE, DETECTED_LANGUAGE, OCR_CONFIDENCE, PROCESSING_MS, PROCESSING_STATUS)
                VALUES
                  (ATTACHMENT_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSED')
                """,
                messageId,
                attachment.fileName(),
                attachment.contentType(),
                sha256(attachment.bytes()),
                result.path("pdf_type").asText("UNKNOWN"),
                result.path("detected_language").asText("unknown"),
                result.path("ocr_confidence").isNumber() ? result.path("ocr_confidence").asDouble() : null,
                result.path("processing_ms").asLong(0));
    }

    private void persistUnsupportedAttachment(long messageId, MailAttachment attachment) {
        jdbc.update("""
                INSERT INTO ATTACHMENT
                  (ID, MESSAGE_ID, FILE_NAME, MIME_TYPE, PROCESSING_STATUS)
                VALUES
                  (ATTACHMENT_SEQ.NEXTVAL, ?, ?, ?, 'LOGGED_UNSUPPORTED')
                """, messageId, attachment.fileName(), attachment.contentType());
    }

    private void persistClassifications(long messageId, JsonNode result) {
        for (JsonNode item : result.path("classifications")) {
            jdbc.update("""
                    INSERT INTO CLASSIFICATION
                      (ID, MESSAGE_ID, CATEGORY, CONFIDENCE, REASON, MODEL_NAME, CREATED_AT)
                    VALUES
                      (CLASSIFICATION_SEQ.NEXTVAL, ?, ?, ?, ?, 'prototype-pipeline', ?)
                    """,
                    messageId,
                    item.path("category").asText(),
                    item.path("confidence").asDouble(0),
                    item.path("reason").asText(""),
                    Instant.now());
        }
    }

    private void persistFacts(long messageId, JsonNode result) {
        Iterator<Map.Entry<String, JsonNode>> groups = result.path("extracted_facts").fields();
        while (groups.hasNext()) {
            Map.Entry<String, JsonNode> group = groups.next();
            Iterator<Map.Entry<String, JsonNode>> fields = group.getValue().fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode valueNode = field.getValue();
                JsonNode source = valueNode.path("source");
                String value = valueNode.path("value").asText("Not stated");
                if (source.isMissingNode() || source.isNull() || "Not stated".equalsIgnoreCase(value)) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO EXTRACTED_FACT
                          (ID, MESSAGE_ID, FACT_GROUP, FIELD_NAME, FIELD_VALUE, CONFIDENCE,
                           SOURCE_TYPE, SOURCE_NAME, SOURCE_PAGE, EVIDENCE_TEXT, CREATED_AT)
                        VALUES
                          (EXTRACTED_FACT_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        messageId,
                        group.getKey(),
                        field.getKey(),
                        value,
                        valueNode.path("confidence").asDouble(0),
                        source.path("source_type").asText("PDF"),
                        source.path("source_name").asText("unknown"),
                        source.path("page").isInt() ? source.path("page").asInt() : null,
                        source.path("evidence").asText(null),
                        Instant.now());
            }
        }
    }

    private void audit(long messageId, String type, String actorType, String detail) {
        jdbc.update("""
                INSERT INTO AUDIT_EVENT
                  (ID, MESSAGE_ID, EVENT_TYPE, ACTOR_TYPE, ACTOR_ID, DETAILS_JSON, CREATED_AT)
                VALUES
                  (AUDIT_EVENT_SEQ.NEXTVAL, ?, ?, ?, 'clinevo-inbox-api', ?, ?)
                """, messageId, type, actorType, "{\"detail\":\"" + jsonEscape(detail) + "\"}", Instant.now());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

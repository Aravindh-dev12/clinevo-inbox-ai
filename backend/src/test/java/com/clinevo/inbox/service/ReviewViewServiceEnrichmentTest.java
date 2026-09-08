package com.clinevo.inbox.service;

import com.clinevo.inbox.api.InboxDetailView;
import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.repository.InboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReviewViewServiceEnrichmentTest {
    @Autowired InboxMessageRepository repository;
    @Autowired ReviewViewService reviewViewService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void detailProjectsPersistedTranslationTablesAndImagesAsStructuredJson() {
        InboxMessage message = new InboxMessage();
        message.setInternetMessageId("<enrichment-" + System.nanoTime() + "@example.test>");
        message.setSender("synthetic@example.test");
        message.setSubject("Synthetic translated attachment");
        message.setReceivedAt(Instant.now());
        message.setBodyText("Synthetic only");
        message.setStatus("READY_FOR_REVIEW");
        message = repository.saveAndFlush(message);

        String translation = """
                {"applied":true,"status":"TRANSLATED","source_language":"es","target_language":"en",
                 "method":"SYNTHETIC_RULES","rationale":"synthetic demo translation","requires_human_review":true,
                 "pages":[{"page":1,"original_text":"Paciente sintético","translated_text":"Synthetic patient"}]}
                """;
        String tables = """
                [{"page":1,"rows":[["Date","ALT"],["05-Aug-2026","28"]]}]
                """;
        String images = """
                [{"page":1,"description":"Embedded image text requires review","requires_human_review":true,
                  "confidence":0.87,"method":"OCR_TEXT","evidence_text":"LOT-A17","width":320,"height":180}]
                """;

        jdbc.update("""
                INSERT INTO ATTACHMENT
                  (ID, MESSAGE_ID, FILE_NAME, MIME_TYPE, SHA256, PDF_TYPE, DETECTED_LANGUAGE,
                   OCR_CONFIDENCE, PROCESSING_MS, PROCESSING_STATUS, MALWARE_SCAN_STATUS, STORAGE_PROVIDER,
                   TRANSLATION_JSON, TABLES_JSON, IMAGES_JSON, CREATED_AT)
                VALUES
                  (ATTACHMENT_SEQ.NEXTVAL, ?, 'synthetic-es.pdf', 'application/pdf', ?, 'NON_ENGLISH', 'es',
                   NULL, 321, 'PROCESSED', 'CLEAN', 'FILESYSTEM', ?, ?, ?, ?)
                """,
                message.getId(), "a".repeat(64), translation, tables, images, Instant.now());

        InboxDetailView detail = reviewViewService.detail(message.getId());

        assertThat(detail.attachments()).hasSize(1);
        InboxDetailView.AttachmentView attachment = detail.attachments().getFirst();
        assertThat(attachment.processingMs()).isEqualTo(321L);
        assertThat(attachment.detectedLanguage()).isEqualTo("es");
        assertThat(attachment.translation().path("status").asText()).isEqualTo("TRANSLATED");
        assertThat(attachment.translation().path("pages").get(0).path("original_text").asText())
                .isEqualTo("Paciente sintético");
        assertThat(attachment.tables().get(0).path("rows").get(1).get(1).asText()).isEqualTo("28");
        assertThat(attachment.images().get(0).path("requires_human_review").asBoolean()).isTrue();
        assertThat(attachment.images().get(0).path("evidence_text").asText()).isEqualTo("LOT-A17");
    }
}

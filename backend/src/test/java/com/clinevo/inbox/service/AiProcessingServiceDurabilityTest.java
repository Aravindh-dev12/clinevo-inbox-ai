package com.clinevo.inbox.service;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.mail.MailAttachment;
import com.clinevo.inbox.repository.InboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiProcessingServiceDurabilityTest {
    @Autowired AiProcessingService service;
    @Autowired InboxMessageRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void enqueueStagesAttachmentBytesAndDurableJob() {
        InboxMessage message = fixture();
        byte[] pdf = "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII);
        service.enqueue(message.getId(), List.of(new MailAttachment("../unsafe/case.pdf", "application/pdf", pdf)));

        assertThat(jdbc.queryForObject("SELECT STATUS FROM PROCESSING_JOB WHERE MESSAGE_ID=?", String.class, message.getId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT PROCESSING_STATUS FROM ATTACHMENT WHERE MESSAGE_ID=?", String.class, message.getId())).isEqualTo("STAGED");
        assertThat(jdbc.queryForObject("SELECT FILE_NAME FROM ATTACHMENT WHERE MESSAGE_ID=?", String.class, message.getId())).isEqualTo("case.pdf");
        assertThat(jdbc.queryForObject("SELECT CONTENT_BLOB FROM ATTACHMENT WHERE MESSAGE_ID=?", byte[].class, message.getId())).containsExactly(pdf);
    }

    @Test
    void fakePdfIsRejectedBeforeAiProcessing() {
        InboxMessage message = fixture();
        service.enqueue(message.getId(), List.of(new MailAttachment("fake.pdf", "application/pdf", "not-pdf".getBytes(StandardCharsets.UTF_8))));
        assertThat(jdbc.queryForObject("SELECT PROCESSING_STATUS FROM ATTACHMENT WHERE MESSAGE_ID=?", String.class, message.getId())).isEqualTo("REJECTED_INVALID_PDF");
        assertThat(jdbc.queryForObject("SELECT REJECTION_REASON FROM ATTACHMENT WHERE MESSAGE_ID=?", String.class, message.getId())).contains("PDF signature");
    }

    @Test
    void failedAttemptIsRequeuedThenBecomesTerminalAtMaxAttempts() {
        InboxMessage message = fixture();
        service.enqueue(message.getId(), List.of());
        Long jobId = jdbc.queryForObject("SELECT ID FROM PROCESSING_JOB WHERE MESSAGE_ID=?", Long.class, message.getId());
        jdbc.update("UPDATE PROCESSING_JOB SET STATUS='PROCESSING', ATTEMPT_COUNT=1 WHERE ID=?", jobId);
        service.handleFailure(jobId, 10, new RuntimeException("synthetic transient"));
        assertThat(jdbc.queryForObject("SELECT STATUS FROM PROCESSING_JOB WHERE ID=?", String.class, jobId)).isEqualTo("QUEUED");
        assertThat(repository.findById(message.getId()).orElseThrow().getStatus()).isEqualTo("RETRY_QUEUED");

        jdbc.update("UPDATE PROCESSING_JOB SET STATUS='PROCESSING', ATTEMPT_COUNT=3 WHERE ID=?", jobId);
        service.handleFailure(jobId, 20, new RuntimeException("synthetic terminal"));
        assertThat(jdbc.queryForObject("SELECT STATUS FROM PROCESSING_JOB WHERE ID=?", String.class, jobId)).isEqualTo("FAILED");
        assertThat(repository.findById(message.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING_FAILED");
    }

    private InboxMessage fixture() {
        InboxMessage message = new InboxMessage();
        message.setInternetMessageId("<durable-" + System.nanoTime() + "@example.test>");
        message.setSender("synthetic@example.test");
        message.setSubject("Synthetic durable job");
        message.setReceivedAt(Instant.now());
        message.setBodyText("A synthetic email body only.");
        message.setStatus("RECEIVED");
        return repository.saveAndFlush(message);
    }
}

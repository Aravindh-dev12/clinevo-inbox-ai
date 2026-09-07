package com.clinevo.inbox.service;

import com.clinevo.inbox.api.FactOverride;
import com.clinevo.inbox.api.ReviewRequest;
import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.repository.InboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InboxServiceTest {
    @Autowired InboxMessageRepository repository;
    @Autowired InboxService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void acceptReviewIsPersistedAndAudited() {
        InboxMessage message = fixture();
        InboxMessage reviewed = service.review(message.getId(), new ReviewRequest("ACCEPT", null, "verified", "reviewer-a", List.of()));
        assertThat(reviewed.getStatus()).isEqualTo("REVIEW_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REVIEW_ACTION WHERE MESSAGE_ID = ?", Long.class, message.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE MESSAGE_ID = ?", Long.class, message.getId())).isEqualTo(1L);
    }

    @Test
    void fieldOverrideChangesValueAndKeepsReviewHistory() {
        InboxMessage message = fixture();
        jdbc.update("INSERT INTO EXTRACTED_FACT (ID,MESSAGE_ID,FACT_GROUP,FIELD_NAME,FIELD_VALUE,CONFIDENCE,SOURCE_TYPE,SOURCE_NAME,SOURCE_PAGE,EVIDENCE_TEXT,CREATED_AT) VALUES (EXTRACTED_FACT_SEQ.NEXTVAL,?,?,?,?,?,?,?,?,?,?)",
                message.getId(), "patient", "age", "44", .90, "PDF", "case.pdf", 2, "44-year-old", Instant.now());
        Long factId = jdbc.queryForObject("SELECT ID FROM EXTRACTED_FACT WHERE MESSAGE_ID = ?", Long.class, message.getId());
        service.review(message.getId(), new ReviewRequest("OVERRIDE", "ICSR", "source says 45", "reviewer-b", List.of(new FactOverride(factId, "45"))));
        assertThat(jdbc.queryForObject("SELECT FIELD_VALUE FROM EXTRACTED_FACT WHERE ID = ?", String.class, factId)).isEqualTo("45");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REVIEW_ACTION WHERE MESSAGE_ID = ?", Long.class, message.getId())).isEqualTo(3L);
    }

    private InboxMessage fixture() {
        InboxMessage message = new InboxMessage();
        message.setInternetMessageId("<test-" + System.nanoTime() + "@example.test>");
        message.setSender("synthetic@example.test");
        message.setSubject("Synthetic case");
        message.setReceivedAt(Instant.now());
        message.setBodyText("Synthetic only");
        message.setStatus("READY_FOR_REVIEW");
        return repository.saveAndFlush(message);
    }
}

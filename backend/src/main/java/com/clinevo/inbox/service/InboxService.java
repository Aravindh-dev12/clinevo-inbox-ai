package com.clinevo.inbox.service;

import com.clinevo.inbox.api.FactOverride;
import com.clinevo.inbox.api.ReviewRequest;
import com.clinevo.inbox.config.RequestCorrelationFilter;
import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.metrics.OperationalMetrics;
import com.clinevo.inbox.repository.InboxMessageRepository;
import com.clinevo.inbox.security.ReviewerIdentityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class InboxService {
    private static final Set<String> VALID_CATEGORIES = Set.of("ICSR", "PQC", "MI", "NOT_RELEVANT");

    private final InboxMessageRepository repository;
    private final JdbcTemplate jdbc;
    private final AiProcessingService processing;
    private final ReviewerIdentityService reviewerIdentity;
    private final OperationalMetrics metrics;

    public InboxService(InboxMessageRepository repository, JdbcTemplate jdbc, AiProcessingService processing,
                        ReviewerIdentityService reviewerIdentity, OperationalMetrics metrics) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.processing = processing;
        this.reviewerIdentity = reviewerIdentity;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<InboxMessage> list() { return repository.findAll(); }

    @Transactional(readOnly = true)
    public InboxMessage get(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Inbox item not found: " + id));
    }

    @Transactional
    public InboxMessage queue(long id) {
        InboxMessage item = get(id);
        processing.requeueExisting(id);
        item.setStatus("QUEUED");
        audit(id, "MESSAGE_REQUEUED", "USER", "manual durable processing request");
        return repository.save(item);
    }

    @Transactional
    public InboxMessage review(long id, ReviewRequest request) {
        long started = System.nanoTime();
        InboxMessage item = get(id);
        String normalized = request.action().trim().toUpperCase();
        if (!List.of("ACCEPT", "OVERRIDE").contains(normalized)) throw new IllegalArgumentException("action must be ACCEPT or OVERRIDE");
        String reviewer = reviewerIdentity.resolve(request.reviewer());

        List<String> overrideCategories = resolveOverrideCategories(request);
        if ("OVERRIDE".equals(normalized) && !overrideCategories.isEmpty()) {
            insertReview(id, reviewer, "CATEGORY_OVERRIDE", "classification", null,
                    String.join(",", overrideCategories), request.note());
        }

        for (FactOverride override : request.factOverrides()) {
            String previous = jdbc.queryForObject("SELECT FIELD_VALUE FROM EXTRACTED_FACT WHERE ID = ? AND MESSAGE_ID = ?", (rs, row) -> rs.getString(1), override.factId(), id);
            jdbc.update("UPDATE EXTRACTED_FACT SET FIELD_VALUE = ? WHERE ID = ? AND MESSAGE_ID = ?", override.newValue(), override.factId(), id);
            insertReview(id, reviewer, "FIELD_OVERRIDE", "fact:" + override.factId(), previous, override.newValue(), request.note());
        }

        insertReview(id, reviewer, normalized, null, null, null, request.note());
        item.setStatus("ACCEPT".equals(normalized) ? "REVIEW_ACCEPTED" : "REVIEW_OVERRIDDEN");
        audit(id, "REVIEW_" + normalized, "HUMAN",
                "reviewer=" + reviewer + (overrideCategories.isEmpty() ? "" : ", categories=" + String.join(",", overrideCategories)));
        InboxMessage saved = repository.save(item);
        metrics.recordReview(normalized, (System.nanoTime() - started) / 1_000_000);
        return saved;
    }

    private List<String> resolveOverrideCategories(ReviewRequest request) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String category : request.overrideCategories()) {
            if (category == null || category.isBlank()) continue;
            String value = category.trim().toUpperCase();
            if (!VALID_CATEGORIES.contains(value)) throw new IllegalArgumentException("invalid overrideCategories value: " + category);
            normalized.add(value);
        }
        if (normalized.isEmpty() && request.overrideCategory() != null && !request.overrideCategory().isBlank()) {
            String legacy = request.overrideCategory().trim().toUpperCase();
            if (!VALID_CATEGORIES.contains(legacy)) throw new IllegalArgumentException("invalid overrideCategory");
            normalized.add(legacy);
        }
        if (normalized.contains("NOT_RELEVANT") && normalized.size() > 1) {
            throw new IllegalArgumentException("NOT_RELEVANT cannot be combined with ICSR, PQC, or MI");
        }
        return new ArrayList<>(normalized);
    }

    private void insertReview(long id, String reviewer, String action, String field, String previous, String next, String note) {
        jdbc.update("""
                INSERT INTO REVIEW_ACTION
                  (ID, MESSAGE_ID, REVIEWER, ACTION_TYPE, FIELD_NAME, PREVIOUS_VALUE, NEW_VALUE, NOTE, CREATED_AT)
                VALUES
                  (REVIEW_ACTION_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, reviewer, action, field, previous, next, note, Instant.now());
    }

    private void audit(long id, String type, String actorType, String detail) {
        String requestId = RequestCorrelationFilter.currentRequestId();
        String payload = "{\"requestId\":" + (requestId == null ? "null" : "\"" + jsonEscape(requestId) + "\"") + ",\"detail\":\"" + jsonEscape(detail) + "\"}";
        jdbc.update("""
                INSERT INTO AUDIT_EVENT
                  (ID, MESSAGE_ID, EVENT_TYPE, ACTOR_TYPE, ACTOR_ID, DETAILS_JSON, CREATED_AT)
                VALUES
                  (AUDIT_EVENT_SEQ.NEXTVAL, ?, ?, ?, 'clinevo-review-api', ?, ?)
                """, id, type, actorType, payload, Instant.now());
    }

    private String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
}

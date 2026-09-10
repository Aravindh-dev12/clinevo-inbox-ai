package com.clinevo.inbox.service;

import com.clinevo.inbox.api.InboxQueueItem;
import com.clinevo.inbox.domain.InboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewQueueService {
    private final InboxService inbox;
    private final JdbcTemplate jdbc;
    private final ReviewPriorityPolicy priorityPolicy;

    public ReviewQueueService(InboxService inbox, JdbcTemplate jdbc, ReviewPriorityPolicy priorityPolicy) {
        this.inbox = inbox;
        this.jdbc = jdbc;
        this.priorityPolicy = priorityPolicy;
    }

    @Transactional(readOnly = true)
    public List<InboxQueueItem> list() {
        List<InboxMessage> messages = inbox.list();
        if (messages.isEmpty()) return List.of();

        Map<Long, List<InboxQueueItem.QueueClassification>> classifications = loadClassifications();
        Map<Long, AttachmentRisk> attachmentRisks = loadAttachmentRisks();
        Map<Long, FactCoverage> factCoverage = loadFactCoverage();

        List<InboxQueueItem> items = new ArrayList<>(messages.size());
        for (InboxMessage message : messages) {
            long id = message.getId();
            List<InboxQueueItem.QueueClassification> messageClassifications = classifications.getOrDefault(id, List.of());
            AttachmentRisk attachmentRisk = attachmentRisks.getOrDefault(id, AttachmentRisk.NONE);
            FactCoverage coverage = factCoverage.get(id);
            BigDecimal evidenceCoverage = coverage == null || coverage.asserted == 0
                    ? null
                    : BigDecimal.valueOf((double) coverage.sourced / coverage.asserted).setScale(2, RoundingMode.HALF_UP);

            ReviewPriorityPolicy.Assessment assessment = priorityPolicy.assess(
                    message.getStatus(),
                    messageClassifications,
                    attachmentRisk.minOcrConfidence,
                    attachmentRisk.nonEnglish,
                    evidenceCoverage == null ? null : evidenceCoverage.doubleValue());

            items.add(new InboxQueueItem(
                    message.getId(), message.getInternetMessageId(), message.getSender(), message.getSubject(),
                    message.getReceivedAt(), message.getStatus(), message.getAiSummary(), message.getProcessingMs(),
                    messageClassifications, assessment.score(), assessment.band(), assessment.reasons(), evidenceCoverage));
        }

        items.sort((left, right) -> {
            int byPriority = Integer.compare(right.reviewPriorityScore(), left.reviewPriorityScore());
            if (byPriority != 0) return byPriority;
            if (left.receivedAt() == null && right.receivedAt() == null) return 0;
            if (left.receivedAt() == null) return 1;
            if (right.receivedAt() == null) return -1;
            return right.receivedAt().compareTo(left.receivedAt());
        });
        return List.copyOf(items);
    }

    private Map<Long, List<InboxQueueItem.QueueClassification>> loadClassifications() {
        Map<Long, List<InboxQueueItem.QueueClassification>> result = new HashMap<>();
        jdbc.query("""
                SELECT MESSAGE_ID, CATEGORY, CONFIDENCE, REASON
                FROM CLASSIFICATION
                ORDER BY MESSAGE_ID, CONFIDENCE DESC, ID
                """, rs -> {
            result.computeIfAbsent(rs.getLong("MESSAGE_ID"), ignored -> new ArrayList<>())
                    .add(new InboxQueueItem.QueueClassification(
                            rs.getString("CATEGORY"), rs.getBigDecimal("CONFIDENCE"), rs.getString("REASON")));
        });
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    private Map<Long, AttachmentRisk> loadAttachmentRisks() {
        Map<Long, AttachmentRisk> result = new HashMap<>();
        jdbc.query("""
                SELECT MESSAGE_ID,
                       MIN(OCR_CONFIDENCE) AS MIN_OCR_CONFIDENCE,
                       MAX(CASE
                             WHEN DETECTED_LANGUAGE IS NOT NULL
                              AND LOWER(DETECTED_LANGUAGE) NOT IN ('en', 'eng', 'english', 'unknown')
                             THEN 1 ELSE 0
                           END) AS HAS_NON_ENGLISH
                FROM ATTACHMENT
                GROUP BY MESSAGE_ID
                """, rs -> {
            BigDecimal ocr = rs.getBigDecimal("MIN_OCR_CONFIDENCE");
            result.put(rs.getLong("MESSAGE_ID"), new AttachmentRisk(
                    ocr == null ? null : ocr.doubleValue(), rs.getInt("HAS_NON_ENGLISH") > 0));
        });
        return result;
    }

    private Map<Long, FactCoverage> loadFactCoverage() {
        Map<Long, FactCoverage> result = new HashMap<>();
        jdbc.query("""
                SELECT MESSAGE_ID, FIELD_VALUE, SOURCE_TYPE, SOURCE_NAME, SOURCE_PAGE
                FROM EXTRACTED_FACT
                """, rs -> {
            String value = clobText(rs.getObject("FIELD_VALUE"));
            if (value == null || value.isBlank() || "Not stated".equalsIgnoreCase(value.trim())) return;

            FactCoverage coverage = result.computeIfAbsent(rs.getLong("MESSAGE_ID"), ignored -> new FactCoverage());
            coverage.asserted++;
            String sourceType = rs.getString("SOURCE_TYPE");
            boolean hasSource = sourceType != null && rs.getString("SOURCE_NAME") != null;
            if (hasSource && (!"PDF".equalsIgnoreCase(sourceType) || rs.getObject("SOURCE_PAGE") != null)) {
                coverage.sourced++;
            }
        });
        return result;
    }

    private static String clobText(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Clob clob) return clob.getSubString(1, (int) clob.length());
        return value.toString();
    }

    private record AttachmentRisk(Double minOcrConfidence, boolean nonEnglish) {
        private static final AttachmentRisk NONE = new AttachmentRisk(null, false);
    }

    private static final class FactCoverage {
        private int asserted;
        private int sourced;
    }
}

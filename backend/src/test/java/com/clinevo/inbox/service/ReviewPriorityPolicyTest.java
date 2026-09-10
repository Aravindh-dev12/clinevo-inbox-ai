package com.clinevo.inbox.service;

import com.clinevo.inbox.api.InboxQueueItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPriorityPolicyTest {
    private final ReviewPriorityPolicy policy = new ReviewPriorityPolicy();

    @Test
    void escalates_uncertain_multilabel_safety_content() {
        var assessment = policy.assess(
                "READY_FOR_REVIEW",
                List.of(
                        classification("ICSR", 0.71),
                        classification("PQC", 0.86)),
                0.69,
                true,
                0.75);

        assertEquals(100, assessment.score());
        assertEquals("HIGH", assessment.band());
        assertTrue(assessment.reasons().stream().anyMatch(value -> value.contains("Safety-bearing")));
        assertTrue(assessment.reasons().stream().anyMatch(value -> value.contains("OCR")));
        assertTrue(assessment.reasons().stream().anyMatch(value -> value.contains("provenance")));
    }

    @Test
    void keeps_confident_not_relevant_content_routine() {
        var assessment = policy.assess(
                "READY_FOR_REVIEW",
                List.of(classification("NOT_RELEVANT", 0.95)),
                null,
                false,
                null);

        assertEquals(0, assessment.score());
        assertEquals("ROUTINE", assessment.band());
        assertTrue(assessment.reasons().isEmpty());
    }

    @Test
    void makes_processing_failure_visible() {
        var assessment = policy.assess("PROCESSING_FAILED", List.of(), null, false, null);
        assertEquals(40, assessment.score());
        assertEquals("ATTENTION", assessment.band());
        assertTrue(assessment.reasons().getFirst().contains("manual recovery"));
    }

    private static InboxQueueItem.QueueClassification classification(String category, double confidence) {
        return new InboxQueueItem.QueueClassification(category, BigDecimal.valueOf(confidence), "synthetic test reason");
    }
}

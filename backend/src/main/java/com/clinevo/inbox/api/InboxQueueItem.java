package com.clinevo.inbox.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Reviewer-queue projection. The priority score is an attention-routing aid,
 * not a clinical seriousness or regulatory reportability decision.
 */
public record InboxQueueItem(
        Long id,
        String internetMessageId,
        String sender,
        String subject,
        Instant receivedAt,
        String status,
        String aiSummary,
        Long processingMs,
        List<QueueClassification> classifications,
        int reviewPriorityScore,
        String reviewPriorityBand,
        List<String> attentionReasons,
        BigDecimal evidenceCoverage
) {
    public record QueueClassification(String category, BigDecimal confidence, String reason) {}
}

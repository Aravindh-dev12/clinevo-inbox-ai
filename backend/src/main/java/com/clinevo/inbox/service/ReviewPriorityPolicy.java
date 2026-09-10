package com.clinevo.inbox.service;

import com.clinevo.inbox.api.InboxQueueItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Transparent reviewer-attention policy. It deliberately uses only observable
 * workflow/quality signals and must never be interpreted as clinical urgency.
 */
@Component
public class ReviewPriorityPolicy {
    public Assessment assess(String status,
                             List<InboxQueueItem.QueueClassification> classifications,
                             Double minOcrConfidence,
                             boolean nonEnglish,
                             Double evidenceCoverage) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        Set<String> categories = new HashSet<>();
        double minClassificationConfidence = 1.0;

        for (InboxQueueItem.QueueClassification classification : classifications) {
            categories.add(classification.category());
            if (classification.confidence() != null) {
                minClassificationConfidence = Math.min(minClassificationConfidence, classification.confidence().doubleValue());
            }
        }

        if ("PROCESSING_FAILED".equalsIgnoreCase(status)) {
            score += 40;
            reasons.add("Processing failed; manual recovery required");
        }
        if (categories.contains("ICSR")) {
            score += 40;
            reasons.add("Safety-bearing content requires reviewer confirmation");
        } else if (categories.contains("PQC")) {
            score += 20;
        } else if (categories.contains("MI")) {
            score += 10;
        }
        if (categories.size() > 1) {
            score += 15;
            reasons.add("Multi-label case needs cross-workflow review");
        }
        if (!classifications.isEmpty() && minClassificationConfidence < 0.75) {
            score += 20;
            reasons.add("Classification confidence below 75%");
        }
        if (minOcrConfidence != null && minOcrConfidence < 0.75) {
            score += 15;
            reasons.add("OCR confidence below 75%");
        }
        if (nonEnglish) {
            score += 10;
            reasons.add("Non-English source requires translation-aware review");
        }
        if (evidenceCoverage != null && evidenceCoverage < 0.85) {
            score += 20;
            reasons.add("Some asserted facts lack complete source provenance");
        }

        score = Math.min(score, 100);
        String band = score >= 60 ? "HIGH" : score >= 25 ? "ATTENTION" : "ROUTINE";
        return new Assessment(score, band, List.copyOf(reasons));
    }

    public record Assessment(int score, String band, List<String> reasons) {}
}

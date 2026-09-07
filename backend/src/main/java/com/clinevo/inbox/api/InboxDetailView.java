package com.clinevo.inbox.api;

import com.clinevo.inbox.domain.InboxMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InboxDetailView(
        InboxMessage message,
        List<ClassificationView> classifications,
        List<AttachmentView> attachments,
        List<FactView> facts,
        List<ReviewActionView> reviewActions
) {
    public record ClassificationView(String category, BigDecimal confidence, String reason) {}
    public record AttachmentView(Long id, String fileName, String mimeType, String pdfType,
                                 String detectedLanguage, BigDecimal ocrConfidence,
                                 Long processingMs, String processingStatus) {}
    public record FactView(Long id, String factGroup, String fieldName, String fieldValue,
                           BigDecimal confidence, String sourceType, String sourceName,
                           Integer sourcePage, String evidenceText) {}
    public record ReviewActionView(String actionType, String reviewer, String fieldName,
                                   String previousValue, String newValue, String note, Instant createdAt) {}
}

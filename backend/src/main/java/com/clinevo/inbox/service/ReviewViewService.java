package com.clinevo.inbox.service;

import com.clinevo.inbox.api.InboxDetailView;
import com.clinevo.inbox.domain.InboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Clob;
import java.sql.SQLException;
import java.util.List;

@Service
public class ReviewViewService {
    private final JdbcTemplate jdbc;
    private final InboxService inbox;

    public ReviewViewService(JdbcTemplate jdbc, InboxService inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    @Transactional(readOnly = true)
    public InboxDetailView detail(long id) {
        InboxMessage message = inbox.get(id);
        List<InboxDetailView.ClassificationView> classifications = jdbc.query("""
                SELECT CATEGORY, CONFIDENCE, REASON
                FROM CLASSIFICATION WHERE MESSAGE_ID = ? ORDER BY CONFIDENCE DESC, ID
                """, (rs, row) -> new InboxDetailView.ClassificationView(
                rs.getString("CATEGORY"), rs.getBigDecimal("CONFIDENCE"), rs.getString("REASON")), id);

        List<InboxDetailView.AttachmentView> attachments = jdbc.query("""
                SELECT ID, FILE_NAME, MIME_TYPE, PDF_TYPE, DETECTED_LANGUAGE, OCR_CONFIDENCE, PROCESSING_MS, PROCESSING_STATUS
                FROM ATTACHMENT WHERE MESSAGE_ID = ? ORDER BY ID
                """, (rs, row) -> new InboxDetailView.AttachmentView(
                rs.getLong("ID"), rs.getString("FILE_NAME"), rs.getString("MIME_TYPE"), rs.getString("PDF_TYPE"),
                rs.getString("DETECTED_LANGUAGE"), rs.getBigDecimal("OCR_CONFIDENCE"),
                nullableLong(rs.getObject("PROCESSING_MS")), rs.getString("PROCESSING_STATUS")), id);

        List<InboxDetailView.FactView> facts = jdbc.query("""
                SELECT ID, FACT_GROUP, FIELD_NAME, FIELD_VALUE, CONFIDENCE, SOURCE_TYPE, SOURCE_NAME, SOURCE_PAGE, EVIDENCE_TEXT
                FROM EXTRACTED_FACT WHERE MESSAGE_ID = ? ORDER BY FACT_GROUP, FIELD_NAME, ID
                """, (rs, row) -> new InboxDetailView.FactView(
                rs.getLong("ID"), rs.getString("FACT_GROUP"), rs.getString("FIELD_NAME"), clobText(rs.getObject("FIELD_VALUE")),
                rs.getBigDecimal("CONFIDENCE"), rs.getString("SOURCE_TYPE"), rs.getString("SOURCE_NAME"),
                nullableInteger(rs.getObject("SOURCE_PAGE")), rs.getString("EVIDENCE_TEXT")), id);

        List<InboxDetailView.ReviewActionView> actions = jdbc.query("""
                SELECT ACTION_TYPE, REVIEWER, FIELD_NAME, PREVIOUS_VALUE, NEW_VALUE, NOTE, CREATED_AT
                FROM REVIEW_ACTION WHERE MESSAGE_ID = ? ORDER BY CREATED_AT DESC, ID DESC
                """, (rs, row) -> new InboxDetailView.ReviewActionView(
                rs.getString("ACTION_TYPE"), rs.getString("REVIEWER"), rs.getString("FIELD_NAME"),
                clobText(rs.getObject("PREVIOUS_VALUE")), clobText(rs.getObject("NEW_VALUE")), rs.getString("NOTE"),
                rs.getTimestamp("CREATED_AT").toInstant()), id);

        return new InboxDetailView(message, classifications, attachments, facts, actions);
    }

    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Integer nullableInteger(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static String clobText(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Clob clob) return clob.getSubString(1, (int) clob.length());
        return value.toString();
    }
}

package com.clinevo.inbox.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "INBOX_MESSAGE")
public class InboxMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "msg_seq")
    @SequenceGenerator(name = "msg_seq", sequenceName = "INBOX_MESSAGE_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "INTERNET_MESSAGE_ID", unique = true, length = 512)
    private String internetMessageId;

    @Column(name = "SENDER_ADDRESS", nullable = false, length = 512)
    private String sender;

    @Column(name = "SUBJECT_LINE", length = 1000)
    private String subject;

    @Column(name = "RECEIVED_AT", nullable = false)
    private Instant receivedAt;

    @Lob
    @Column(name = "BODY_TEXT")
    private String bodyText;

    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = "RECEIVED";

    @Lob
    @Column(name = "AI_SUMMARY")
    private String aiSummary;

    @Column(name = "PROCESSING_MS")
    private Long processingMs;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getInternetMessageId() { return internetMessageId; }
    public void setInternetMessageId(String value) { this.internetMessageId = value; }
    public String getSender() { return sender; }
    public void setSender(String value) { this.sender = value; }
    public String getSubject() { return subject; }
    public void setSubject(String value) { this.subject = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant value) { this.receivedAt = value; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String value) { this.bodyText = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String value) { this.aiSummary = value; }
    public Long getProcessingMs() { return processingMs; }
    public void setProcessingMs(Long value) { this.processingMs = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

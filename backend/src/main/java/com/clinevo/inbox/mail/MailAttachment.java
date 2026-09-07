package com.clinevo.inbox.mail;

public record MailAttachment(String fileName, String contentType, byte[] bytes, String rejectionReason, long originalSize) {
    public MailAttachment(String fileName, String contentType, byte[] bytes) {
        this(fileName, contentType, bytes, null, bytes == null ? 0 : bytes.length);
    }

    public static MailAttachment rejected(String fileName, String contentType, String reason, long originalSize) {
        return new MailAttachment(fileName, contentType, new byte[0], reason, originalSize);
    }

    public boolean isPdf() {
        return "application/pdf".equalsIgnoreCase(contentType)
                || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
    }

    public boolean rejected() {
        return rejectionReason != null && !rejectionReason.isBlank();
    }
}

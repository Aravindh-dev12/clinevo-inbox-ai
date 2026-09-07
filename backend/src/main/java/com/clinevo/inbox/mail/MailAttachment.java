package com.clinevo.inbox.mail;

public record MailAttachment(String fileName, String contentType, byte[] bytes) {
    public boolean isPdf() {
        return "application/pdf".equalsIgnoreCase(contentType)
                || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
    }
}

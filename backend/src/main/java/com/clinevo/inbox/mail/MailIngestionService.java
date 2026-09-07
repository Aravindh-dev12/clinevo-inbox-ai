package com.clinevo.inbox.mail;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.repository.InboxMessageRepository;
import com.clinevo.inbox.service.AiProcessingService;
import jakarta.mail.*;
import jakarta.mail.search.FlagTerm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Service
public class MailIngestionService {
    private final InboxMessageRepository messages;
    private final AiProcessingService processing;

    @Value("${clinevo.mail.host:}") private String host;
    @Value("${clinevo.mail.port:993}") private int port;
    @Value("${clinevo.mail.username:}") private String username;
    @Value("${clinevo.mail.password:}") private String password;
    @Value("${clinevo.mail.folder:INBOX}") private String folderName;
    @Value("${clinevo.mail.ssl:true}") private boolean ssl;
    @Value("${clinevo.max-attachment-bytes:20971520}") private long maxAttachmentBytes;

    public MailIngestionService(InboxMessageRepository messages, AiProcessingService processing) {
        this.messages = messages;
        this.processing = processing;
    }

    @Scheduled(fixedDelayString = "#{${clinevo.mail-poll-seconds:60} * 1000}")
    public void poll() {
        if (host.isBlank() || username.isBlank() || password.isBlank()) return;
        String protocol = ssl ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".ssl.enable", Boolean.toString(ssl));
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "30000");
        try {
            Session session = Session.getInstance(props);
            try (Store store = session.getStore(protocol)) {
                store.connect(host, port, username, password);
                try (Folder folder = store.getFolder(folderName)) {
                    folder.open(Folder.READ_WRITE);
                    for (Message raw : folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))) ingest(raw);
                }
            }
        } catch (Exception ex) {
            System.err.println("Mailbox poll failed: " + ex.getClass().getSimpleName());
        }
    }

    private void ingest(Message raw) throws Exception {
        String messageId = firstHeader(raw, "Message-ID");
        Optional<InboxMessage> duplicate = messageId == null ? Optional.empty() : messages.findByInternetMessageId(messageId);
        if (duplicate.isPresent() && processing.hasActiveOrSuccessfulJob(duplicate.get().getId())) {
            raw.setFlag(Flags.Flag.SEEN, true);
            return;
        }

        ParsedContent parsed = new ParsedContent();
        parsePart(raw, parsed);

        InboxMessage message;
        if (duplicate.isPresent()) {
            message = duplicate.get();
        } else {
            message = new InboxMessage();
            message.setInternetMessageId(messageId);
            message.setSender(raw.getFrom() != null && raw.getFrom().length > 0 ? raw.getFrom()[0].toString() : "unknown");
            message.setSubject(raw.getSubject());
            message.setReceivedAt(raw.getReceivedDate() != null ? raw.getReceivedDate().toInstant() : Instant.now());
            message.setBodyText(parsed.body.toString().trim());
            message.setStatus("RECEIVED");
            message = messages.save(message);
        }

        processing.enqueue(message.getId(), List.copyOf(parsed.attachments));
        raw.setFlag(Flags.Flag.SEEN, true);
    }

    private void parsePart(Part part, ParsedContent output) throws Exception {
        if (part.isMimeType("text/plain") && part.getFileName() == null) {
            Object content = part.getContent();
            if (content instanceof String text) {
                if (!output.body.isEmpty()) output.body.append('\n');
                output.body.append(text);
            }
            return;
        }
        if (part.isMimeType("text/html") && output.body.isEmpty() && part.getFileName() == null) {
            Object content = part.getContent();
            if (content instanceof String html) output.body.append(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) parsePart(multipart.getBodyPart(i), output);
            return;
        }
        String fileName = part.getFileName();
        if (fileName != null) {
            String contentType = part.getContentType().split(";", 2)[0];
            int declaredSize = part.getSize();
            if (declaredSize > maxAttachmentBytes) {
                output.attachments.add(MailAttachment.rejected(fileName, contentType, "attachment exceeds configured size limit", declaredSize));
                return;
            }
            try (InputStream in = part.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxAttachmentBytes) {
                        output.attachments.add(MailAttachment.rejected(fileName, contentType, "attachment exceeds configured size limit", total));
                        return;
                    }
                    bytes.write(buffer, 0, read);
                }
                output.attachments.add(new MailAttachment(fileName, contentType, bytes.toByteArray()));
            }
        }
    }

    private String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static class ParsedContent {
        private final StringBuilder body = new StringBuilder();
        private final List<MailAttachment> attachments = new ArrayList<>();
    }
}

package com.clinevo.inbox.api;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.service.InboxService;
import com.clinevo.inbox.service.ReviewQueueService;
import com.clinevo.inbox.service.ReviewViewService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InboxController {
    private final InboxService inboxService;
    private final ReviewViewService reviewViewService;
    private final ReviewQueueService reviewQueueService;

    public InboxController(InboxService inboxService, ReviewViewService reviewViewService, ReviewQueueService reviewQueueService) {
        this.inboxService = inboxService;
        this.reviewViewService = reviewViewService;
        this.reviewQueueService = reviewQueueService;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok", "service", "clinevo-inbox-api"); }

    @GetMapping("/inbox") public List<InboxQueueItem> list() { return reviewQueueService.list(); }
    @GetMapping("/inbox/{id}") public InboxMessage get(@PathVariable long id) { return inboxService.get(id); }
    @GetMapping("/inbox/{id}/detail") public InboxDetailView detail(@PathVariable long id) { return reviewViewService.detail(id); }

    @GetMapping("/inbox/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable long id, @PathVariable long attachmentId) {
        ReviewViewService.AttachmentContent attachment = reviewViewService.attachment(id, attachmentId);
        MediaType mediaType;
        try {
            mediaType = attachment.mimeType() == null || attachment.mimeType().isBlank()
                    ? MediaType.APPLICATION_PDF
                    : MediaType.parseMediaType(attachment.mimeType());
        } catch (IllegalArgumentException ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(attachment.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(attachment.content());
    }

    @PostMapping("/inbox/{id}/process") public InboxMessage queue(@PathVariable long id) { return inboxService.queue(id); }
    @PostMapping("/inbox/{id}/review") public InboxMessage review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) { return inboxService.review(id, request); }
}

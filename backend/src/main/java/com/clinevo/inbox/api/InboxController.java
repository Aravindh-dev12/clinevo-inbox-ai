package com.clinevo.inbox.api;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.service.InboxService;
import com.clinevo.inbox.service.ReviewViewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${clinevo.cors-origin:http://localhost:4200}")
public class InboxController {
    private final InboxService inboxService;
    private final ReviewViewService reviewViewService;

    public InboxController(InboxService inboxService, ReviewViewService reviewViewService) {
        this.inboxService = inboxService;
        this.reviewViewService = reviewViewService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "clinevo-inbox-api");
    }

    @GetMapping("/inbox")
    public List<InboxMessage> list() {
        return inboxService.list();
    }

    @GetMapping("/inbox/{id}")
    public InboxMessage get(@PathVariable long id) {
        return inboxService.get(id);
    }

    @GetMapping("/inbox/{id}/detail")
    public InboxDetailView detail(@PathVariable long id) {
        return reviewViewService.detail(id);
    }

    @PostMapping("/inbox/{id}/process")
    public InboxMessage queue(@PathVariable long id) {
        return inboxService.queue(id);
    }

    @PostMapping("/inbox/{id}/review")
    public InboxMessage review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return inboxService.review(id, request);
    }
}

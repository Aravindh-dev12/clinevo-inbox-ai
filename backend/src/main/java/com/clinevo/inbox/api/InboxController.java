package com.clinevo.inbox.api;

import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.service.InboxService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class InboxController {
    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
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

    @PostMapping("/inbox/{id}/process")
    public InboxMessage queue(@PathVariable long id) {
        return inboxService.queue(id);
    }

    @PostMapping("/inbox/{id}/review")
    public InboxMessage review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return inboxService.review(id, request);
    }
}

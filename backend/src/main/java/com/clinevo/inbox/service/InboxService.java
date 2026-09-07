package com.clinevo.inbox.service;

import com.clinevo.inbox.api.ReviewRequest;
import com.clinevo.inbox.domain.InboxMessage;
import com.clinevo.inbox.repository.InboxMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InboxService {
    private final InboxMessageRepository repository;

    public InboxService(InboxMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<InboxMessage> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public InboxMessage get(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inbox item not found: " + id));
    }

    @Transactional
    public InboxMessage queue(long id) {
        InboxMessage item = get(id);
        item.setStatus("QUEUED");
        return repository.save(item);
    }

    @Transactional
    public InboxMessage review(long id, ReviewRequest request) {
        InboxMessage item = get(id);
        String normalized = request.action().trim().toUpperCase();
        item.setStatus(switch (normalized) {
            case "ACCEPT" -> "REVIEW_ACCEPTED";
            case "OVERRIDE" -> "REVIEW_OVERRIDDEN";
            default -> throw new IllegalArgumentException("action must be ACCEPT or OVERRIDE");
        });
        return repository.save(item);
    }
}

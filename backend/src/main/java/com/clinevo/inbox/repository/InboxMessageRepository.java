package com.clinevo.inbox.repository;

import com.clinevo.inbox.domain.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, Long> {
    Optional<InboxMessage> findByInternetMessageId(String internetMessageId);
}

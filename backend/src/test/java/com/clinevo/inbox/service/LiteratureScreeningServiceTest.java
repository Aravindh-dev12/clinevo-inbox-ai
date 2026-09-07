package com.clinevo.inbox.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LiteratureScreeningServiceTest {
    @Test
    void rejectsEmptyBatchBeforeCallingAiService() {
        LiteratureScreeningService service = new LiteratureScreeningService(RestClient.builder(), "http://localhost:9", 1024);
        assertThrows(IllegalArgumentException.class, () -> service.screen(List.of()));
    }
}

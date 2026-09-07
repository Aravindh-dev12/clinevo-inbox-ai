package com.clinevo.inbox.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OperationalMetricsTest {
    @Test
    void reviewMetricsUseOnlyLowCardinalityActionTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry, mock(JdbcTemplate.class));
        metrics.recordReview("OVERRIDE", 12);
        assertThat(registry.counter("clinevo.review.actions", "action", "override").count()).isEqualTo(1.0);
        assertThat(registry.timer("clinevo.review.duration").count()).isEqualTo(1L);
    }
}

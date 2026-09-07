package com.clinevo.inbox.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OperationalMetrics {
    private final MeterRegistry registry;

    public OperationalMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        Gauge.builder("clinevo.processing.queue.depth", jdbc, template -> count(template, "SELECT COUNT(*) FROM PROCESSING_JOB WHERE STATUS='QUEUED'"))
                .description("Durable processing jobs waiting to be claimed").register(registry);
        Gauge.builder("clinevo.processing.jobs.processing", jdbc, template -> count(template, "SELECT COUNT(*) FROM PROCESSING_JOB WHERE STATUS='PROCESSING'"))
                .description("Durable processing jobs currently leased").register(registry);
        Gauge.builder("clinevo.processing.jobs.failed", jdbc, template -> count(template, "SELECT COUNT(*) FROM PROCESSING_JOB WHERE STATUS='FAILED'"))
                .description("Terminal failed processing jobs").register(registry);
        Gauge.builder("clinevo.inbox.messages.total", jdbc, template -> count(template, "SELECT COUNT(*) FROM INBOX_MESSAGE"))
                .description("Stored inbox messages; no message content is exported as a metric label").register(registry);
        Gauge.builder("clinevo.processing.duration.last.ms", jdbc, template -> nullableNumber(template, "SELECT MAX(PROCESSING_MS) FROM INBOX_MESSAGE WHERE PROCESSING_MS IS NOT NULL"))
                .description("Largest persisted message processing duration in milliseconds").register(registry);
        Gauge.builder("clinevo.processing.retries.total", jdbc, template -> count(template, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE EVENT_TYPE='AI_PROCESSING_RETRY'"))
                .description("Persisted retry audit events").register(registry);
        Gauge.builder("clinevo.attachments.scan.pending", jdbc, template -> count(template, "SELECT COUNT(*) FROM ATTACHMENT WHERE MALWARE_SCAN_STATUS='PENDING'"))
                .description("Attachments still waiting for a malware decision").register(registry);
        Gauge.builder("clinevo.attachments.quarantined", jdbc, template -> count(template, "SELECT COUNT(*) FROM ATTACHMENT WHERE PROCESSING_STATUS='QUARANTINED_MALWARE'"))
                .description("Attachments quarantined after malware detection").register(registry);
        Gauge.builder("clinevo.attachments.storage.external", jdbc, template -> count(template, "SELECT COUNT(*) FROM ATTACHMENT WHERE STORAGE_PROVIDER='FILESYSTEM' AND PURGED_AT IS NULL"))
                .description("Attachment objects currently retained in external immutable storage").register(registry);
        Gauge.builder("clinevo.attachments.purged.total", jdbc, template -> count(template, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE EVENT_TYPE='ATTACHMENT_CONTENT_PURGED'"))
                .description("Audited attachment-content purge events").register(registry);
    }

    public void recordReview(String action, long elapsedMs) {
        String normalized = "OVERRIDE".equalsIgnoreCase(action) ? "override" : "accept";
        registry.counter("clinevo.review.actions", "action", normalized).increment();
        Timer.builder("clinevo.review.duration").description("Reviewer API processing duration")
                .register(registry).record(Duration.ofMillis(Math.max(0, elapsedMs)));
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double nullableNumber(JdbcTemplate jdbc, String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}

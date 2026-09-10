package com.clinevo.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTimeConfigurationTest {

    @Test
    void instantArgumentsUseExplicitTimestampWithTimeZoneBinding() {
        Instant instant = Instant.parse("2026-09-10T12:30:00Z");

        Object[] normalized = JdbcTimeConfiguration.normalizeJdbcArguments(
                new Object[]{42L, instant, "unchanged"});

        assertThat(normalized[0]).isEqualTo(42L);
        assertThat(normalized[2]).isEqualTo("unchanged");
        assertThat(normalized[1]).isInstanceOf(SqlParameterValue.class);

        SqlParameterValue timestamp = (SqlParameterValue) normalized[1];
        assertThat(timestamp.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(timestamp.getValue()).isEqualTo(instant.atOffset(ZoneOffset.UTC));
    }
}

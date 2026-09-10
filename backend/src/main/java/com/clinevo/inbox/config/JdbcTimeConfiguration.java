package com.clinevo.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class JdbcTimeConfiguration {

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new InstantAwareJdbcTemplate(dataSource);
    }

    static Object[] normalizeJdbcArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) return arguments;
        Object[] normalized = arguments.clone();
        for (int i = 0; i < normalized.length; i++) {
            if (normalized[i] instanceof Instant instant) {
                // Oracle does not accept java.time.Instant through PreparedStatement#setObject.
                // PROCESSING_JOB and audit timestamps are TIMESTAMP WITH TIME ZONE, so bind an
                // explicit JDBC 4.2 type while preserving the instant at UTC.
                normalized[i] = new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        instant.atOffset(ZoneOffset.UTC));
            }
        }
        return normalized;
    }

    static final class InstantAwareJdbcTemplate extends JdbcTemplate {
        InstantAwareJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            return super.update(sql, normalizeJdbcArguments(args));
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return super.query(sql, rowMapper, normalizeJdbcArguments(args));
        }
    }
}

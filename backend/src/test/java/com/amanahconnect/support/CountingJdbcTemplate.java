package com.amanahconnect.support;

import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.StatementCallback;

/**
 * A JdbcTemplate that counts the statements it runs, so a test can prove that a page costs the same handful of queries however much data there is
 * (no query per row). Replaces the auto-configured one in tests; behaves exactly like it otherwise.
 */
@Configuration
public class CountingJdbcTemplate {

    public static final AtomicLong STATEMENTS = new AtomicLong();

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource) {
            @Override
            public <T> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action) throws DataAccessException {
                STATEMENTS.incrementAndGet();
                return super.execute(psc, action);
            }

            @Override
            public <T> T execute(StatementCallback<T> action) throws DataAccessException {
                STATEMENTS.incrementAndGet();
                return super.execute(action);
            }
        };
    }
}

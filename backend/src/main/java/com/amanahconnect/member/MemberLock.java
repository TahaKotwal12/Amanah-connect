package com.amanahconnect.member;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises everything that adds members to one community (create, approve, import) until the transaction ends, so the
 * plan's member limit is checked against a count nobody else is changing.
 */
@Component
public class MemberLock {

    private final NamedParameterJdbcTemplate jdbc;

    public MemberLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID communityId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "members:" + communityId), rs -> { });
    }
}

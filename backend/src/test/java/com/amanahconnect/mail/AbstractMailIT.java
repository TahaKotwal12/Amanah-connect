package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.FakeSmtpGateway;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Helpers for tests of the email engine: queue a mail straight into the outbox, run the sender, read the row back. */
public abstract class AbstractMailIT extends AbstractDeskIT {

    @Autowired protected EmailSender sender;
    @Autowired protected FakeSmtpGateway smtp;
    @Autowired protected JsonMapper json;

    /** Mail left behind by other tests must not be in the way of the sender's batches. */
    @BeforeEach
    void emptyTheOutboxAndTheFakeServer() {
        jdbc.update("update email_outbox set status = 'SENT', sent_at = now() where status = 'PENDING'");
        jdbc.update("delete from email_suppressions where source <> 'KEEP'");
        smtp.reset();
    }

    protected UUID queue(UUID communityId, String to, String template, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into email_outbox (id, community_id, to_email, template, payload) values (?, ?, ?, ?, ?::jsonb)", id, communityId, to, template, toJson(payload));
        return id;
    }

    protected UUID queueSample(UUID communityId, String to, String template) {
        return queue(communityId, to, template, MailSamples.payload(template));
    }

    protected String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    protected Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select status, attempts, error, next_attempt_at, last_attempt_at, sent_at, ses_message_id, payload::text as payload from email_outbox where id = ?", id);
    }

    protected String status(UUID id) {
        return (String) row(id).get("status");
    }

    protected void makeDueNow(UUID id) {
        jdbc.update("update email_outbox set next_attempt_at = now() - interval '1 second' where id = ?", id);
    }

    protected Instant instant(Object timestamp) {
        return ((java.sql.Timestamp) timestamp).toInstant();
    }

    protected void assertSentOnce(UUID id) {
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(row(id).get("sent_at")).isNotNull();
    }
}

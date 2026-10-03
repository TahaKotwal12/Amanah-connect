package com.amanahconnect.mail;

import com.amanahconnect.notification.EmailSuppression;
import com.amanahconnect.notification.EmailSuppressionRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends what is waiting in the outbox. It is only ever called by the scheduled worker, never from a request.
 *
 * <p><b>Safe on several instances.</b> A run first reserves a batch in one statement ({@code FOR UPDATE SKIP LOCKED}): it counts the
 * attempt and pushes {@code next_attempt_at} forward by the lease, so another instance skips those rows, and a worker that dies mid-send
 * only delays its rows by the lease (the attempt was already counted, so a mail that crashes workers cannot loop for ever). The SMTP call
 * happens outside any database transaction.
 *
 * <p><b>Retries.</b> A temporary failure schedules the next attempt after 1, 2, 4, 8, 16 minutes (doubling from {@code backoffBase});
 * after {@code maxAttempts} the mail is FAILED with the last error. A permanent failure (the server refused the message or address, an
 * unknown template, missing data, a suppressed address) is FAILED at once. Mail with a secret in its payload (a reset or invite link)
 * has the payload erased as soon as it is sent or has failed for good.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    public record Result(int claimed, int sent, int retrying, int failed) {}

    private record Row(UUID id, UUID communityId, String to, String template, Map<String, Object> payload, int attempts) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MailTemplates templates;
    private final MailRenderer renderer;
    private final BrandingResolver branding;
    private final MailAttachments attachments;
    private final SmtpGateway gateway;
    private final EmailSuppressionRepository suppressions;
    private final EmailProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public EmailSender(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MailTemplates templates,
            MailRenderer renderer,
            BrandingResolver branding,
            MailAttachments attachments,
            SmtpGateway gateway,
            EmailSuppressionRepository suppressions,
            EmailProperties properties,
            JsonMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.templates = templates;
        this.renderer = renderer;
        this.branding = branding;
        this.attachments = attachments;
        this.gateway = gateway;
        this.suppressions = suppressions;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public Result runOnce() {
        Instant now = clock.instant();
        List<Row> batch = claim(now);
        int sent = 0, retrying = 0, failed = 0;
        for (Row row : batch) {
            try {
                switch (deliver(row)) {
                    case SENT -> sent++;
                    case RETRY -> retrying++;
                    case FAILED -> failed++;
                }
            } catch (RuntimeException e) {
                // Whatever went wrong, this row must not stay reserved with nobody looking after it.
                log.error("Email {} could not be processed: {}", row.id(), e.toString());
                try {
                    if (recordFailure(row, "Unexpected error: " + e, false) == Outcome.FAILED) failed++;
                    else retrying++;
                } catch (RuntimeException inner) {
                    log.error("Email {} could not even be marked: {}", row.id(), inner.toString());
                }
            }
        }
        if (!batch.isEmpty()) log.info("Email run: {} taken, {} sent, {} to retry, {} failed", batch.size(), sent, retrying, failed);
        return new Result(batch.size(), sent, retrying, failed);
    }

    // ---- claiming -------------------------------------------------------------------------------------------------------

    private List<Row> claim(Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("leaseEnd", Timestamp.from(now.plus(properties.lease())))
                .addValue("limit", properties.batchSize());
        return transaction.execute(status -> jdbc.query(
                "UPDATE email_outbox SET attempts = attempts + 1, last_attempt_at = :now, next_attempt_at = :leaseEnd"
                        + " WHERE id IN (SELECT id FROM email_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, community_id, to_email::text AS to_email, template, payload::text AS payload, attempts",
                params, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class), rs.getString("to_email"), rs.getString("template"),
                        parse(rs.getString("payload")), rs.getInt("attempts"))));
    }

    private Map<String, Object> parse(String payload) {
        try {
            return payload == null ? new HashMap<>() : json.readValue(payload, MAP);
        } catch (RuntimeException e) {
            return new HashMap<>();
        }
    }

    // ---- one mail -------------------------------------------------------------------------------------------------------

    private enum Outcome { SENT, RETRY, FAILED }

    private Outcome deliver(Row row) {
        MailTemplate template = templates.find(row.template()).orElse(null);
        if (template == null) return recordFailure(row, "Unknown email template: " + row.template(), true);

        var suppressed = suppressions.findByEmail(row.to());
        if (suppressed.isPresent()) {
            return recordFailure(row, "SUPPRESSED: " + suppressed.get().getReason() + " (this address is on the suppression list)", true);
        }

        OutgoingMail mail;
        try {
            mail = prepare(row, template);
        } catch (MailRenderException e) {
            return recordFailure(row, e.getMessage(), true);
        }
        try {
            String messageId = gateway.send(mail);
            markSent(row, template, messageId);
            return Outcome.SENT;
        } catch (SmtpFailure e) {
            return recordFailure(row, e.getMessage(), e.permanent());
        }
    }

    private OutgoingMail prepare(Row row, MailTemplate template) {
        MailBranding brand = branding.resolve(row.communityId(), template.audience());
        var attachment = attachments.forTemplate(row.template(), row.communityId(), row.payload());
        RenderedMail rendered = renderer.render(row.template(), row.payload(), brand, attachment.isPresent());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Auto-Submitted", "auto-generated");
        headers.put("X-Amanah-Template", row.template());
        headers.put("X-Amanah-Email-Id", row.id().toString());
        if (!properties.sesConfigurationSet().isBlank()) headers.put("X-SES-CONFIGURATION-SET", properties.sesConfigurationSet());
        if (brand.memberFacing() && brand.contactEmail() != null && !brand.contactEmail().isBlank()) {
            headers.put("List-Unsubscribe", "<mailto:" + brand.contactEmail() + "?subject=Unsubscribe>");
        }
        String displayFrom = brand.memberFacing() ? displayName(brand.name()) + " via Amanah Connect" : null;
        return new OutgoingMail(
                from(displayFrom), row.to(), brand.replyTo(), rendered.subject(), rendered.html(), rendered.text(), headers,
                brand.hasLogo() ? new OutgoingMail.Inline(MailBranding.LOGO_CID, brand.logoBytes(), brand.logoContentType()) : null,
                attachment.map(List::of).orElse(List.of()));
    }

    /** The configured From address, with the community's name as the display name for member mail. */
    private String from(String displayName) {
        try {
            var configured = jakarta.mail.internet.InternetAddress.parse(properties.from())[0];
            if (displayName == null) return configured.toString();
            return new jakarta.mail.internet.InternetAddress(configured.getAddress(), displayName, "UTF-8").toString();
        } catch (Exception e) {
            return properties.from();
        }
    }

    private static String displayName(String name) {
        return name == null ? "" : name.replaceAll("[\\p{Cntrl}\"<>@]", " ").replaceAll("\\s+", " ").trim();
    }

    // ---- results --------------------------------------------------------------------------------------------------------

    private void markSent(Row row, MailTemplate template, String messageId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", row.id())
                .addValue("now", Timestamp.from(clock.instant()))
                .addValue("sesId", messageId)
                .addValue("scrub", template.sensitive());
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE email_outbox SET status = 'SENT', sent_at = :now, ses_message_id = :sesId, error = NULL, next_attempt_at = :now,"
                        + " payload = CASE WHEN :scrub THEN '{\"erased\": true}'::jsonb ELSE payload END WHERE id = :id AND status = 'PENDING'",
                params));
    }

    private Outcome recordFailure(Row row, String error, boolean permanent) {
        boolean exhausted = permanent || row.attempts() >= properties.maxAttempts();
        String message = error == null ? "" : error.length() > 1000 ? error.substring(0, 1000) : error;
        boolean sensitive = templates.find(row.template()).map(MailTemplate::sensitive).orElse(false);
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource("id", row.id()).addValue("error", message).addValue("now", Timestamp.from(now));
        if (exhausted) {
            params.addValue("scrub", sensitive);
            transaction.executeWithoutResult(status -> jdbc.update(
                    "UPDATE email_outbox SET status = 'FAILED', error = :error,"
                            + " payload = CASE WHEN :scrub THEN '{\"erased\": true}'::jsonb ELSE payload END WHERE id = :id AND status = 'PENDING'", params));
            log.warn("Email {} ({}) failed for good after {} attempt(s): {}", row.id(), row.template(), row.attempts(), message);
            return Outcome.FAILED;
        }
        params.addValue("next", Timestamp.from(now.plus(backoff(row.attempts()))));
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE email_outbox SET error = :error, next_attempt_at = :next WHERE id = :id AND status = 'PENDING'", params));
        log.info("Email {} ({}) will be retried (attempt {} failed): {}", row.id(), row.template(), row.attempts(), message);
        return Outcome.RETRY;
    }

    /** 1, 2, 4, 8, 16 minutes with the default base; never more than six hours. */
    Duration backoff(int attemptsSoFar) {
        long factor = 1L << Math.min(Math.max(attemptsSoFar - 1, 0), 20);
        Duration delay = properties.backoffBase().multipliedBy(factor);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}

package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.notification.SuppressionReason;
import com.amanahconnect.support.ApiClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EmailSenderIT extends AbstractMailIT {

    @Autowired SuppressionService suppressions;
    @Autowired EmailOutboxJob job;
    @Autowired LockProvider lockProvider;

    // ---- sending ------------------------------------------------------------------------------------------------------------

    @Test
    void sendsAPendingMailAndMarksItSent() {
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");

        EmailSender.Result result = sender.runOnce();

        assertThat(result.sent()).isGreaterThanOrEqualTo(1);
        assertSentOnce(id);
        assertThat(row(id).get("ses_message_id")).asString().startsWith("fake-message-");
        assertThat(row(id).get("error")).isNull();
        OutgoingMail mail = smtp.sentTo(to).get(0);
        assertThat(mail.subject()).isEqualTo("Welcome to Lotus Residents Welfare Association");
        assertThat(mail.html()).contains("Welcome to Lotus Residents Welfare Association");
        assertThat(mail.text()).contains("Dear Asha Rao");
    }

    @Test
    void nothingIsSentDuringTheRequestThatQueuedIt() {
        String to = email();

        member(sessionA, "Queued Only", to, true); // queues the welcome email in the same transaction

        assertThat(count("select count(*) from email_outbox where to_email = ? and template = 'member-welcome' and status = 'PENDING'", to)).isEqualTo(1);
        assertThat(smtp.sentTo(to)).as("the request thread never talks to SMTP").isEmpty();
        sender.runOnce();
        assertThat(smtp.sentTo(to)).hasSize(1);
    }

    @Test
    void memberMailCarriesTheCommunitysNameContactAndReplyTo() {
        jdbc.update("update communities set name = 'Lotus Residents', contact_email = 'office@lotus.example', contact_phone = '+91 98765 43210' where id = ?", communityA.getId());
        String to = email();
        queueSample(communityA.getId(), to, "member-bill");

        sender.runOnce();

        OutgoingMail mail = smtp.sentTo(to).get(0);
        assertThat(mail.from()).contains("Lotus Residents via Amanah Connect").contains("no-reply@");
        assertThat(mail.replyTo()).isEqualTo("office@lotus.example");
        assertThat(mail.headers()).containsEntry("Auto-Submitted", "auto-generated").containsEntry("X-Amanah-Template", "member-bill");
        assertThat(mail.headers().get("List-Unsubscribe")).isEqualTo("<mailto:office@lotus.example?subject=Unsubscribe>");
        assertThat(mail.html()).contains("Lotus Residents", "office@lotus.example", "+91 98765 43210", "contact the community admin");
        assertThat(mail.text()).contains("office@lotus.example", "contact the community admin");
    }

    @Test
    void platformMailCarriesThePlatformsNameAndNoReplyTo() {
        String to = email();
        queueSample(null, to, "password-reset");

        sender.runOnce();

        OutgoingMail mail = smtp.sentTo(to).get(0);
        assertThat(mail.from()).doesNotContain(" via Amanah Connect");
        assertThat(mail.replyTo()).isNull();
        assertThat(mail.headers()).doesNotContainKey("List-Unsubscribe");
        assertThat(mail.logo()).as("the platform's own logo").isNotNull();
        assertThat(mail.html()).contains("Amanah Connect").doesNotContain("You are receiving this email because you are a member");
    }

    @Test
    void theCommunitysLogoIsEmbeddedInTheHeader() {
        String key = "communities/" + communityA.getId() + "/logo/" + UUID.randomUUID() + ".png";
        storage.put(key, pngBytes(), "image/png");
        jdbc.update("update communities set logo_key = ? where id = ?", key, communityA.getId());
        String to = email();
        queueSample(communityA.getId(), to, "member-welcome");

        sender.runOnce();

        OutgoingMail mail = smtp.sentTo(to).get(0);
        assertThat(mail.logo()).isNotNull();
        assertThat(mail.logo().cid()).isEqualTo("brand-logo");
        assertThat(mail.logo().contentType()).isEqualTo("image/png");
        assertThat(mail.html()).contains("cid:brand-logo");
    }

    @Test
    void aMissingLogoFileJustMeansNoLogo() {
        jdbc.update("update communities set logo_key = ? where id = ?", "communities/" + communityA.getId() + "/logo/" + UUID.randomUUID() + ".png", communityA.getId());
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");

        sender.runOnce();

        assertSentOnce(id);
        assertThat(smtp.sentTo(to).get(0).logo()).isNull();
        assertThat(smtp.sentTo(to).get(0).html()).doesNotContain("cid:brand-logo");
    }

    @Test
    void aReceiptEmailCarriesTheReceiptPdf() {
        UUID member = member(sessionA, "Receipt Reader", email(), true);
        String to = jdbc.queryForObject("select email::text from members where id = ?", String.class, member);
        UUID invoice = id(invoiceA(member, "800.00", TODAY.plusDays(5)));
        payOk(sessionA, invoice, "800.00");
        assertThat(count("select count(*) from email_outbox where to_email = ? and template = 'member-receipt'", to)).isEqualTo(1);

        sender.runOnce();

        OutgoingMail mail = smtp.sentTo(to).stream().filter(m -> m.headers().get("X-Amanah-Template").equals("member-receipt")).findFirst().orElseThrow();
        assertThat(mail.attachments()).hasSize(1);
        assertThat(mail.attachments().get(0).fileName()).startsWith("Receipt-").endsWith(".pdf");
        assertThat(mail.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(new String(mail.attachments().get(0).bytes(), 0, 4)).isEqualTo("%PDF");
        assertThat(mail.text()).contains("attached");
    }

    // ---- retries and backoff -----------------------------------------------------------------------------------------------

    @Test
    void aTemporaryFailureIsRetriedAfter1Then2Then4Minutes() {
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");
        smtp.failNext(3);

        long[] expectedMinutes = {1, 2, 4};
        for (int attempt = 1; attempt <= 3; attempt++) {
            sender.runOnce();
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(attempt);
            assertThat(row.get("error")).asString().contains("421");
            Duration wait = Duration.between(instant(row.get("last_attempt_at")), instant(row.get("next_attempt_at")));
            assertThat(wait).as("delay after attempt " + attempt).isBetween(Duration.ofMinutes(expectedMinutes[attempt - 1]).minusSeconds(2), Duration.ofMinutes(expectedMinutes[attempt - 1]).plusSeconds(2));
            makeDueNow(id);
        }
        sender.runOnce();

        assertSentOnce(id);
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(4);
        assertThat(row(id).get("error")).as("a later success clears the error").isNull();
    }

    @Test
    void aMailThatCannotBeSentIsFailedAfterSixAttempts() {
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");
        smtp.failAll(false, "421 service not available");
        List<Long> minutes = new ArrayList<>();

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(status(id)).as("before attempt " + attempt).isEqualTo("PENDING");
            sender.runOnce();
            Map<String, Object> row = row(id);
            if (attempt < 6) minutes.add(Math.round(Duration.between(instant(row.get("last_attempt_at")), instant(row.get("next_attempt_at"))).toSeconds() / 60.0));
            makeDueNow(id);
        }

        assertThat(minutes).containsExactly(1L, 2L, 4L, 8L, 16L);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(6);
        assertThat(row.get("error")).asString().contains("421 service not available");
        smtp.reset();
        sender.runOnce();
        assertThat(smtp.sentTo(to)).as("a failed mail is not tried again by itself").isEmpty();
    }

    @Test
    void aPermanentRejectionFailsAtOnceWithoutRetrying() {
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");
        smtp.failFor(to, true);

        sender.runOnce();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(1);
        assertThat(row(id).get("error")).asString().contains("rejected");
    }

    @Test
    void anUnknownTemplateOrMissingDataFailsWithoutCallingSmtp() {
        String to = email();
        UUID unknown = queue(communityA.getId(), to, "no-such-template", Map.of("x", 1));
        UUID missing = queue(communityA.getId(), to, "member-bill", Map.of("communityName", "X"));

        sender.runOnce();

        assertThat(status(unknown)).isEqualTo("FAILED");
        assertThat(row(unknown).get("error")).asString().contains("Unknown email template");
        assertThat(status(missing)).isEqualTo("FAILED");
        assertThat(row(missing).get("error")).asString().contains("needs \"memberName\"");
        assertThat(smtp.sentTo(to)).isEmpty();
    }

    @Test
    void aMailIsNotTakenBeforeItsTimeOrWhileItIsLeasedToAnotherWorker() {
        String to = email();
        UUID waiting = queueSample(communityA.getId(), to, "member-welcome");
        jdbc.update("update email_outbox set next_attempt_at = now() + interval '5 minutes' where id = ?", waiting);

        sender.runOnce();
        assertThat(status(waiting)).isEqualTo("PENDING");
        assertThat(((Number) row(waiting).get("attempts")).intValue()).isZero();
        assertThat(smtp.sentTo(to)).isEmpty();

        // A worker that died after taking the mail: its lease runs out and the mail comes back (the attempt it used up still counts).
        jdbc.update("update email_outbox set attempts = 1, next_attempt_at = now() - interval '1 minute' where id = ?", waiting);
        sender.runOnce();
        assertSentOnce(waiting);
        assertThat(((Number) row(waiting).get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void takingAMailReservesItAndCountsTheAttempt() {
        UUID id = queueSample(communityA.getId(), email(), "member-welcome");
        smtp.failAll(false, "slow");

        sender.runOnce();

        Instant next = instant(row(id).get("next_attempt_at"));
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(1);
        assertThat(next).isAfter(Instant.now().plusSeconds(30));
    }

    // ---- several workers at once -----------------------------------------------------------------------------------------

    @Test
    void severalWorkersNeverSendTheSameMailTwice() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(queueSample(communityA.getId(), email(), "member-welcome"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<EmailSender.Result>> runs = new ArrayList<>();
            for (int i = 0; i < 8; i++) runs.add(sender::runOnce);
            for (Future<EmailSender.Result> f : pool.invokeAll(runs)) f.get();
        } finally {
            pool.shutdownNow();
        }

        Set<String> emailIds = new HashSet<>();
        smtp.sent.forEach(m -> assertThat(emailIds.add(m.headers().get("X-Amanah-Email-Id"))).as("sent twice: " + m.to()).isTrue());
        assertThat(emailIds).hasSize(40);
        for (UUID id : ids) {
            assertThat(status(id)).isEqualTo("SENT");
            assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(1);
        }
    }

    @Test
    void aRunTakesAtMostOneBatch() {
        for (int i = 0; i < 60; i++) queueSample(communityA.getId(), email(), "member-welcome");

        EmailSender.Result first = sender.runOnce();
        EmailSender.Result second = sender.runOnce();

        assertThat(first.claimed()).isEqualTo(50);
        assertThat(second.claimed()).isEqualTo(10);
    }

    @Test
    void theJobRunsUnderAShedLockEvery30Seconds() throws Exception {
        var scheduled = EmailOutboxJob.class.getMethod("run").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        var lock = EmailOutboxJob.class.getMethod("run").getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(scheduled.cron()).isEqualTo("*/30 * * * * *");
        assertThat(lock.name()).isEqualTo("emailOutboxJob");

        UUID id = queueSample(communityA.getId(), email(), "member-welcome");
        var held = lockProvider.lock(new LockConfiguration(Instant.now(), "emailOutboxJob", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();
        try {
            job.run();
            assertThat(status(id)).as("another instance holds the lock").isEqualTo("PENDING");
        } finally {
            held.get().unlock();
        }
        job.run();
        assertThat(status(id)).isEqualTo("SENT");
    }

    // ---- suppression -------------------------------------------------------------------------------------------------------

    @Test
    void aSuppressedAddressIsNeverEmailed() {
        String bad = email();
        String good = email();
        suppressions.suppress(bad.toUpperCase(), SuppressionReason.BOUNCE, "SES", Map.of());
        UUID blocked = queueSample(communityA.getId(), bad, "member-bill");
        UUID fine = queueSample(communityA.getId(), good, "member-bill");

        sender.runOnce();

        assertThat(status(blocked)).isEqualTo("FAILED");
        assertThat(row(blocked).get("error")).asString().startsWith("SUPPRESSED: BOUNCE");
        assertThat(((Number) row(blocked).get("attempts")).intValue()).isEqualTo(1);
        assertThat(smtp.sentTo(bad)).isEmpty();
        assertThat(status(fine)).isEqualTo("SENT");
        // later mail to it, of any kind, is stopped too
        UUID later = queueSample(null, bad, "password-reset");
        sender.runOnce();
        assertThat(status(later)).isEqualTo("FAILED");
        assertThat(smtp.sentTo(bad)).isEmpty();
    }

    @Test
    void failedMailDoesNotCountAgainstTheQuota() {
        String bad = email();
        suppressions.suppress(bad, SuppressionReason.COMPLAINT, "SES", Map.of());
        long before = count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED'", communityA.getId());
        queueSample(communityA.getId(), bad, "member-bill");
        sender.runOnce();

        assertThat(count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED'", communityA.getId())).isEqualTo(before);
    }

    // ---- secrets in the payload --------------------------------------------------------------------------------------------

    @Test
    void linksWithTokensAreErasedOnceTheMailIsSent() {
        UUID reset = queueSample(null, email(), "password-reset");
        UUID bill = queueSample(communityA.getId(), email(), "member-bill");
        UUID welcome = queueSample(communityA.getId(), email(), "member-welcome");

        sender.runOnce();

        assertThat(row(reset).get("payload").toString()).doesNotContain("SAMPLE").contains("erased");
        assertThat(row(bill).get("payload").toString()).as("the pay link carries a token").doesNotContain("/pay/").contains("erased");
        assertThat(row(welcome).get("payload").toString()).as("nothing secret in a welcome").contains("Asha Rao");
    }

    @Test
    void linksWithTokensAreErasedWhenTheMailFailsForGood() {
        String to = email();
        UUID id = queueSample(null, to, "invitation");
        smtp.failFor(to, true);

        sender.runOnce();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(row(id).get("payload").toString()).doesNotContain("SAMPLE").contains("erased");
    }

    @Test
    void aMailBeingRetriedKeepsItsPayload() {
        String to = email();
        UUID id = queueSample(null, to, "password-reset");
        smtp.failNext(1);

        sender.runOnce();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(row(id).get("payload").toString()).contains("SAMPLE");
        makeDueNow(id);
        sender.runOnce();

        assertThat(status(id)).isEqualTo("SENT");
        assertThat(smtp.sentTo(to).get(0).html()).contains("token=SAMPLE");
    }

    private static byte[] pngBytes() {
        return com.amanahconnect.file.MagicBytes.sample("image/png");
    }

    @Test
    void theRealApiStillQueuesInsteadOfSending() {
        ApiClient.Response r = call(sessionA, "POST", "/api/v1/community/members", Map.of("fullName", "Another", "email", email(), "consentEmail", true));
        assertThat(r.status()).isEqualTo(201);
        assertThat(smtp.sent).isEmpty();
    }
}

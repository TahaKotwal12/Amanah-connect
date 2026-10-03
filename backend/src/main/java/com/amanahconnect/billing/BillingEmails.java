package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.PayLinkView;
import com.amanahconnect.community.Community;
import com.amanahconnect.member.Member;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.notification.NotificationSettingsRepository;
import com.amanahconnect.plan.PlanLimitKeys;
import com.amanahconnect.plan.PlanLimitService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Bills and receipts to members, queued in the outbox on the community's email quota. Automatic emails go only to
 * members who have an address AND have agreed to email; over the plan's quota the invoice or payment still happens and
 * the email is skipped. A {@link Session} counts the quota once, so a bulk run does not query it per member.
 */
@Component
public class BillingEmails {

    public static final String BILL = "member-bill";
    public static final String RECEIPT = "member-receipt";

    public enum Outcome { QUEUED, NO_ADDRESS, NO_CONSENT, QUOTA, DISABLED }

    private final EmailOutboxRepository outbox;
    private final NotificationSettingsRepository settings;
    private final PlanLimitService planLimits;

    public BillingEmails(EmailOutboxRepository outbox, NotificationSettingsRepository settings, PlanLimitService planLimits) {
        this.outbox = outbox;
        this.settings = settings;
        this.planLimits = planLimits;
    }

    public Session session(Community community) {
        boolean receiptsOn = settings.findByCommunityId(community.getId()).map(s -> s.isSendReceipt()).orElse(true)
                && planLimits.hasFeature(community.getId(), PlanLimitKeys.FEATURE_RECEIPTS_EMAIL);
        return new Session(community, receiptsOn, planLimits.emailQuotaRemaining(community.getId()));
    }

    public final class Session {
        private final Community community;
        private final boolean receiptsOn;
        private long quotaLeft;

        private Session(Community community, boolean receiptsOn, long quotaLeft) {
            this.community = community;
            this.receiptsOn = receiptsOn;
            this.quotaLeft = quotaLeft;
        }

        /** @param payLink the payment page link to include, or null when there is none */
        public Outcome bill(Invoice invoice, PayLinkView payLink, boolean requireConsent) {
            Member member = invoice.getMember();
            Outcome gate = gate(member, requireConsent);
            if (gate != null) return gate;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("communityName", community.getName());
            payload.put("memberName", member.getFullName());
            payload.put("invoiceNo", invoice.getInvoiceNo());
            payload.put("description", invoice.getDescription());
            payload.put("period", invoice.getPeriod());
            payload.put("currency", community.getCurrency());
            payload.put("amount", invoice.getAmount().toPlainString());
            payload.put("balance", InvoiceStatusRules.balance(invoice.getAmount(), invoice.getAmountPaid()).toString());
            payload.put("dueDate", invoice.getDueDate().toString());
            payload.put("payLink", payLink == null ? null : payLink.url());
            payload.put("contactEmail", community.getContactEmail());
            return queue(member.getEmail(), BILL, payload);
        }

        /** Receipt for a payment; automatic receipts also respect the community's receipt-email setting and plan feature. */
        public Outcome receipt(Receipt receipt, PaymentRecord payment, BigDecimal balanceAfter, boolean automatic) {
            Member member = payment.getMember();
            if (automatic && !receiptsOn) return Outcome.DISABLED;
            Outcome gate = gate(member, automatic);
            if (gate != null) return gate;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("communityName", community.getName());
            payload.put("memberName", member.getFullName());
            payload.put("receiptId", receipt.getId().toString());
            payload.put("receiptNo", receipt.getReceiptNo());
            payload.put("currency", community.getCurrency());
            payload.put("amount", payment.getAmount().toPlainString());
            payload.put("amountInWords", AmountInWords.of(payment.getAmount(), community.getCurrency()));
            payload.put("method", payment.getMethod().name());
            payload.put("receivedOn", payment.getReceivedOn().toString());
            payload.put("invoiceNo", payment.getInvoice() == null ? null : payment.getInvoice().getInvoiceNo());
            payload.put("balance", balanceAfter == null ? null : balanceAfter.toPlainString());
            payload.put("contactEmail", community.getContactEmail());
            return queue(member.getEmail(), RECEIPT, payload);
        }

        private Outcome gate(Member member, boolean requireConsent) {
            if (member == null || member.getEmail() == null) return Outcome.NO_ADDRESS;
            if (requireConsent && !member.isConsentEmail()) return Outcome.NO_CONSENT;
            if (quotaLeft <= 0) return Outcome.QUOTA;
            return null;
        }

        private Outcome queue(String to, String template, Map<String, Object> payload) {
            EmailOutbox mail = new EmailOutbox();
            mail.setCommunityId(community.getId());
            mail.setToEmail(to);
            mail.setTemplate(template);
            mail.setPayload(payload);
            outbox.save(mail);
            if (quotaLeft != Long.MAX_VALUE) quotaLeft--;
            return Outcome.QUEUED;
        }
    }
}

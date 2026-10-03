package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.PublicPayView;
import com.amanahconnect.billing.BillingDtos.UpiPayment;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.file.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public payment-info page behind a pay link. It shows the community, ONE invoice (number, what for, amount, due date,
 * balance) and how to pay by UPI, never anything about other members or invoices, not even the payer's name. Every kind of
 * bad link (unknown, malformed, expired, revoked, cancelled invoice, community not active) gets the same 404.
 *
 * <p>Paying through the link does not mark anything paid. Confirmation is manual: the admin checks their bank or UPI app and
 * records the payment, and the response says so.
 */
@Service
@Transactional(readOnly = true)
public class PublicPayService {

    static final String NOTICE =
            "Paying through this page does not update the bill by itself. The community admin confirms your payment after checking their bank or UPI app, "
                    + "and then marks this invoice paid. Keep your UPI transaction ID until it shows as paid.";

    private static final Logger log = LoggerFactory.getLogger(PublicPayService.class);

    private final PayLinkService payLinks;
    private final InvoiceRepository invoices;
    private final CommunityRepository communities;
    private final UpiPayments upi;
    private final ObjectStorage storage;

    public PublicPayService(PayLinkService payLinks, InvoiceRepository invoices, CommunityRepository communities, UpiPayments upi, ObjectStorage storage) {
        this.payLinks = payLinks;
        this.invoices = invoices;
        this.communities = communities;
        this.upi = upi;
        this.storage = storage;
    }

    public PublicPayView view(String token) {
        PaymentLink link = payLinks.resolve(token);
        if (link == null) {
            throw unavailable();
        }
        Community community = communities.findById(link.getCommunityId()).filter(c -> c.getStatus() == CommunityStatus.ACTIVE).orElseThrow(PublicPayService::unavailable);
        Invoice invoice = invoices.findByIdAndCommunityId(link.getInvoiceId(), link.getCommunityId())
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED && i.getStatus() != InvoiceStatus.DRAFT)
                .orElseThrow(PublicPayService::unavailable);

        boolean payable = InvoiceStatusRules.acceptsPayments(invoice.getStatus());
        UpiPayment upiPayment = upi.tryBuild(community, invoice).orElse(null);
        String logoUrl = null;
        if (community.getLogoKey() != null) {
            try {
                logoUrl = storage.presignDownload(community.getLogoKey());
            } catch (RuntimeException e) {
                log.warn("Could not sign a logo URL for a pay page: {}", e.toString());
            }
        }
        String description = invoice.getDescription() != null ? invoice.getDescription() : (invoice.getFeePlan() == null ? null : invoice.getFeePlan().getName());
        Money amount = Money.of(invoice.getAmount());
        Money paid = Money.of(invoice.getAmountPaid());
        return new PublicPayView(community.getName(), logoUrl, invoice.getInvoiceNo(), description, invoice.getPeriod(), community.getCurrency(), amount, paid, amount.minus(paid),
                invoice.getDueDate(), invoice.getStatus(), payable, upiPayment, true, NOTICE);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.PAY_LINK_UNAVAILABLE, "This payment link is not valid or has expired.");
    }
}

package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.UpiPayment;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.member.QrCodes;
import com.amanahconnect.plan.PlanLimitKeys;
import com.amanahconnect.plan.PlanLimitService;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The UPI deep link and QR code for an invoice's outstanding balance. UPI pays in rupees to the community's UPI ID.
 * Paying through the link does NOT mark anything paid: the admin confirms receipt after checking their bank or UPI app.
 */
@Component
public class UpiPayments {

    static final int QR_SIZE = 360;

    private final PlanLimitService planLimits;

    public UpiPayments(PlanLimitService planLimits) {
        this.planLimits = planLimits;
    }

    /** For the admin: says exactly why there is no QR code. */
    public UpiPayment require(Community community, Invoice invoice) {
        planLimits.requireFeature(community.getId(), PlanLimitKeys.FEATURE_UPI_QR);
        if (community.getUpiId() == null || community.getUpiPayeeName() == null) {
            throw new ApiException(ErrorCode.UPI_NOT_CONFIGURED, "Set the community's UPI ID and payee name in settings first.");
        }
        if (!"INR".equals(community.getCurrency())) {
            throw new ApiException(ErrorCode.UPI_NOT_CONFIGURED, "UPI pays in rupees; this community's currency is " + community.getCurrency() + ".");
        }
        if (!InvoiceStatusRules.acceptsPayments(invoice.getStatus())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "This invoice is " + invoice.getStatus().name().toLowerCase() + " and has nothing to pay.");
        }
        return build(community, invoice);
    }

    /** For the public page and bill emails: a payment option if there is one, nothing otherwise. */
    public Optional<UpiPayment> tryBuild(Community community, Invoice invoice) {
        if (community.getUpiId() == null || community.getUpiPayeeName() == null || !"INR".equals(community.getCurrency())
                || !InvoiceStatusRules.acceptsPayments(invoice.getStatus())
                || !planLimits.hasFeature(community.getId(), PlanLimitKeys.FEATURE_UPI_QR)) {
            return Optional.empty();
        }
        return Optional.of(build(community, invoice));
    }

    public byte[] qrPng(String link) {
        return QrCodes.png(link, QR_SIZE);
    }

    private UpiPayment build(Community community, Invoice invoice) {
        Money balance = InvoiceStatusRules.balance(invoice.getAmount(), invoice.getAmountPaid());
        String link = UpiLinks.build(community.getUpiId(), community.getUpiPayeeName(), balance, invoice.getInvoiceNo());
        return new UpiPayment(community.getUpiPayeeName(), link, Base64.getEncoder().encodeToString(qrPng(link)));
    }
}

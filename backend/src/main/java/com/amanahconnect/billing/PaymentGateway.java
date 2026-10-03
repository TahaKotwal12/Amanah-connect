package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import java.util.UUID;

/**
 * How a payment gets confirmed. Billing records a payment only once its gateway says CONFIRMED, and knows nothing else
 * about how money moves, so an online gateway (cards, UPI collect, net banking) can be added by implementing this
 * interface: no invoice, receipt or ledger code changes.
 *
 * <p>Today there is one implementation, {@link ManualGateway}: the admin has checked their bank or UPI app and confirms
 * the money arrived.
 */
public interface PaymentGateway {

    enum Status { CONFIRMED, PENDING, FAILED }

    /** @param invoiceId null for a donation that is not tied to an invoice */
    record PaymentInstruction(UUID communityId, UUID invoiceId, Money amount, PaymentMethod method, String reference) {}

    /** @param reference the gateway's own reference for the money movement, if it has one */
    record GatewayResult(Status status, String reference, String message) {}

    /** A short stable name, e.g. MANUAL. */
    String id();

    GatewayResult settle(PaymentInstruction instruction);
}

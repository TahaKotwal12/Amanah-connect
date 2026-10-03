package com.amanahconnect.billing;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Picks the gateway for a community. Every community is on the manual gateway until an online one exists. */
@Component
public class PaymentGateways {

    private final PaymentGateway manual;

    public PaymentGateways(List<PaymentGateway> gateways) {
        this.manual = gateways.stream().filter(g -> "MANUAL".equals(g.id())).findFirst().orElseThrow(() -> new IllegalStateException("No manual gateway"));
    }

    public PaymentGateway forCommunity(UUID communityId) {
        return manual;
    }
}

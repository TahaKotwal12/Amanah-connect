package com.amanahconnect.billing;

import org.springframework.stereotype.Component;

/** The admin confirms by hand that the money arrived (cash in hand, a UPI or bank credit they checked). */
@Component
public class ManualGateway implements PaymentGateway {

    @Override
    public String id() {
        return "MANUAL";
    }

    @Override
    public GatewayResult settle(PaymentInstruction instruction) {
        return new GatewayResult(Status.CONFIRMED, instruction.reference(), "Confirmed by the community admin");
    }
}

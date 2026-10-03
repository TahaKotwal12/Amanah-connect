package com.amanahconnect.publicapi;

import com.amanahconnect.billing.BillingDtos.PublicPayView;
import com.amanahconnect.billing.PublicPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/pay")
@Tag(name = "Public · Payment", description = "The payment-info page behind a pay link in a bill email. No login; rate limited per IP.")
public class PublicPayController {

    private final PublicPayService service;

    public PublicPayController(PublicPayService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    @Operation(summary = "Payment info for one invoice", description = "Community name, invoice number, amount due, and the UPI QR and 'pay with UPI app' link. Payment confirmation is MANUAL: the admin marks the invoice paid after checking their bank or UPI app (see `notice`). Any problem with the link gives the same 404 PAY_LINK_UNAVAILABLE.")
    public ResponseEntity<PublicPayView> view(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(token));
    }
}

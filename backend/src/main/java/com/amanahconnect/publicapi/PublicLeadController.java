package com.amanahconnect.publicapi;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.lead.LeadDtos.PublicLeadRequest;
import com.amanahconnect.lead.LeadDtos.PublicLeadResponse;
import com.amanahconnect.lead.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/leads")
@Tag(name = "Public · Leads", description = "The website's request-a-demo form. No login; rate limited per IP.")
public class PublicLeadController {

    private final LeadService service;

    public PublicLeadController(LeadService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @AuditHandledBy("LeadService records LEAD_RECEIVED (nothing is stored when the honeypot is filled)")
    @Operation(summary = "Submit a demo request", description = "Always answers 202 for a well-formed request, including when the honeypot field `website` is filled (the submission is then silently dropped).")
    public PublicLeadResponse submit(@Valid @RequestBody PublicLeadRequest body) {
        service.receive(body);
        return new PublicLeadResponse(true);
    }
}

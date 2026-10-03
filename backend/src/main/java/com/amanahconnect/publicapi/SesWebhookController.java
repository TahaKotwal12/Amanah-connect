package com.amanahconnect.publicapi;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.mail.SesNotificationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Amazon SNS posts bounce and complaint notifications for our SES mail. Public (SNS has no login) but every message is
 * verified against Amazon's signature and the topic allow-list before anything happens. It lives outside {@code /api/v1/public}
 * because SNS messages can be larger than the 16 KB cap that applies there; it has its own cap.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Hidden
public class SesWebhookController {

    private static final int MAX_BYTES = 256 * 1024;

    private final SesNotificationService service;

    public SesWebhookController(SesNotificationService service) {
        this.service = service;
    }

    @PostMapping("/ses")
    @AuditHandledBy("SuppressionService records EMAIL_SUPPRESSED for each address added")
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
        }
        SesNotificationService.Outcome outcome = service.handle(new String(bytes, StandardCharsets.UTF_8));
        return ResponseEntity.ok(Map.of("received", outcome.type(), "suppressed", outcome.suppressed()));
    }
}

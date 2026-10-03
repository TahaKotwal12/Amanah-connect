package com.amanahconnect.mail;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.notification.SuppressionReason;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles what Amazon SNS posts about our mail: it confirms the subscription, and turns permanent bounces and spam complaints into
 * suppressions. Nothing is believed until {@link SnsSignatureVerifier} has verified the message; an unverified message is a 403 with no detail.
 * Temporary ("soft") bounces are ignored: the address may work next time.
 */
@Service
public class SesNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SesNotificationService.class);

    public record Outcome(String type, int suppressed) {}

    private final JsonMapper json;
    private final SnsSignatureVerifier verifier;
    private final SnsCertificateProvider certificates;
    private final SuppressionService suppressions;

    public SesNotificationService(JsonMapper json, SnsSignatureVerifier verifier, SnsCertificateProvider certificates, SuppressionService suppressions) {
        this.json = json;
        this.verifier = verifier;
        this.certificates = certificates;
        this.suppressions = suppressions;
    }

    public Outcome handle(String body) {
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (RuntimeException e) {
            throw forbidden();
        }
        if (message == null || !message.isObject() || !verifier.verify(message)) {
            throw forbidden();
        }
        String type = message.get("Type").asString();
        switch (type) {
            case "SubscriptionConfirmation" -> {
                URI url = verifier.trustedUrl(text(message, "SubscribeURL"));
                if (url == null) throw forbidden();
                certificates.confirmSubscription(url);
                log.info("SNS subscription confirmed for topic {}", text(message, "TopicArn"));
                return new Outcome(type, 0);
            }
            case "Notification" -> {
                return new Outcome(type, handleNotification(text(message, "Message")));
            }
            default -> {
                return new Outcome(type, 0);
            }
        }
    }

    private int handleNotification(String inner) {
        JsonNode event;
        try {
            event = json.readTree(inner == null ? "{}" : inner);
        } catch (RuntimeException e) {
            return 0; // a signed message that is not an SES event (e.g. a test message from the console)
        }
        String kind = firstNonNull(text(event, "notificationType"), text(event, "eventType"));
        String messageId = event.has("mail") ? text(event.get("mail"), "messageId") : null;
        int added = 0;
        if ("Bounce".equals(kind) && event.has("bounce")) {
            JsonNode bounce = event.get("bounce");
            if (!"Permanent".equalsIgnoreCase(String.valueOf(text(bounce, "bounceType")))) return 0;
            for (String address : addresses(bounce.get("bouncedRecipients"))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("bounceType", text(bounce, "bounceType"));
                detail.put("bounceSubType", text(bounce, "bounceSubType"));
                detail.put("messageId", messageId);
                detail.put("timestamp", text(bounce, "timestamp"));
                if (suppressions.suppress(address, SuppressionReason.BOUNCE, "SES", detail)) added++;
            }
        } else if ("Complaint".equals(kind) && event.has("complaint")) {
            JsonNode complaint = event.get("complaint");
            for (String address : addresses(complaint.get("complainedRecipients"))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("feedbackType", text(complaint, "complaintFeedbackType"));
                detail.put("messageId", messageId);
                detail.put("timestamp", text(complaint, "timestamp"));
                if (suppressions.suppress(address, SuppressionReason.COMPLAINT, "SES", detail)) added++;
            }
        }
        return added;
    }

    private static List<String> addresses(JsonNode recipients) {
        List<String> out = new ArrayList<>();
        if (recipients != null && recipients.isArray()) {
            recipients.forEach(r -> {
                String address = text(r, "emailAddress");
                if (address != null) out.add(address);
            });
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "Not accepted.");
    }
}

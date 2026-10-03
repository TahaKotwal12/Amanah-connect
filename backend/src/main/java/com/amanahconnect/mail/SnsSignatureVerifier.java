package com.amanahconnect.mail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Checks that an SNS message really came from Amazon SNS, as documented by AWS: the signing certificate must be at an https
 * amazonaws.com address, and the signature (SHA1withRSA for version 1, SHA256withRSA for version 2) must match the canonical string
 * of the message's fields. Anything that does not check out is rejected, and so is a message from a topic that is not on the allow-list.
 */
@Component
public class SnsSignatureVerifier {

    private static final List<String> NOTIFICATION_FIELDS = List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> SUBSCRIPTION_FIELDS = List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final SnsCertificateProvider certificates;
    private final Pattern certHost;
    private final List<String> allowedTopics;

    public SnsSignatureVerifier(SnsCertificateProvider certificates, EmailProperties properties) {
        this.certificates = certificates;
        this.certHost = Pattern.compile(properties.sns().certHostPattern());
        this.allowedTopics = properties.sns().topicArns();
    }

    /** @return true only if the message is from an allowed topic and carries a valid signature */
    public boolean verify(JsonNode message) {
        try {
            String type = text(message, "Type");
            String topic = text(message, "TopicArn");
            if (type == null || topic == null || !allowedTopics.contains(topic)) return false;
            List<String> fields = switch (type) {
                case "Notification" -> NOTIFICATION_FIELDS;
                case "SubscriptionConfirmation", "UnsubscribeConfirmation" -> SUBSCRIPTION_FIELDS;
                default -> null;
            };
            if (fields == null) return false;
            String algorithm = switch (String.valueOf(text(message, "SignatureVersion"))) {
                case "1" -> "SHA1withRSA";
                case "2" -> "SHA256withRSA";
                default -> null;
            };
            String signature = text(message, "Signature");
            URI certUrl = trustedUrl(text(message, "SigningCertURL"));
            if (algorithm == null || signature == null || certUrl == null || !certUrl.getPath().endsWith(".pem")) return false;

            StringBuilder canonical = new StringBuilder();
            for (String field : fields) {
                String value = text(message, field);
                if (value == null) {
                    if (field.equals("Subject")) continue; // only part of the string when present
                    return false;
                }
                canonical.append(field).append('\n').append(value).append('\n');
            }
            PublicKey key = certificates.publicKey(certUrl);
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    /** An https URL on an Amazon SNS host (no credentials, no odd port), or null. Used for the certificate and for subscription confirmation. */
    public URI trustedUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getHost() == null) return null;
            return certHost.matcher(uri.getHost().toLowerCase()).matches() ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}

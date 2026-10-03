package com.amanahconnect.support;

import com.amanahconnect.mail.SnsCertificateProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Plays Amazon in tests: one RSA key pair whose public half is "the SNS certificate", and a way to sign messages with the private half. */
@Component
@Primary
public class TestSnsCertificateProvider implements SnsCertificateProvider {

    public static final String CERT_URL = "https://sns.ap-south-1.amazonaws.com/SimpleNotificationService-test.pem";
    public static final String TOPIC = "arn:aws:sns:ap-south-1:123456789012:amanah-ses-events";

    private final KeyPair keys;
    private final KeyPair otherKeys;
    public final List<URI> confirmed = new ArrayList<>();

    public TestSnsCertificateProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            otherKeys = generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public PublicKey publicKey(URI certUrl) {
        return keys.getPublic();
    }

    @Override
    public void confirmSubscription(URI subscribeUrl) {
        confirmed.add(subscribeUrl);
    }

    /** The canonical string for the fields AWS signs, in AWS's order. */
    public static String canonical(Map<String, String> message) {
        List<String> fields = message.get("Type").equals("Notification")
                ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type")
                : List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
        StringBuilder out = new StringBuilder();
        for (String field : fields) {
            if (message.get(field) == null) continue;
            out.append(field).append('\n').append(message.get(field)).append('\n');
        }
        return out.toString();
    }

    /** Signs with Amazon's (test) key; version "1" is SHA1withRSA, "2" SHA256withRSA. */
    public String sign(Map<String, String> message, String version) {
        return signWith(keys, message, version);
    }

    /** Signs with some other key: a forgery. */
    public String forge(Map<String, String> message, String version) {
        return signWith(otherKeys, message, version);
    }

    private static String signWith(KeyPair pair, Map<String, String> message, String version) {
        try {
            Signature signature = Signature.getInstance(version.equals("2") ? "SHA256withRSA" : "SHA1withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(canonical(message).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

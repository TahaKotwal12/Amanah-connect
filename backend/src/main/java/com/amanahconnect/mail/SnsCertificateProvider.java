package com.amanahconnect.mail;

import java.net.URI;
import java.security.PublicKey;

/** Fetches the public key SNS signed a message with. Replaceable in tests; the real one only talks to Amazon's own hosts. */
public interface SnsCertificateProvider {

    /**
     * @param certUrl the SigningCertURL from the message, already checked to be an https amazonaws.com address
     * @throws IllegalStateException if the certificate cannot be fetched or read
     */
    PublicKey publicKey(URI certUrl);

    /** Performs the GET that confirms a subscription. */
    void confirmSubscription(URI subscribeUrl);
}

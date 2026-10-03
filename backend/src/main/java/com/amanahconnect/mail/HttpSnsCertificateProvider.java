package com.amanahconnect.mail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Downloads SNS signing certificates over HTTPS (small, cached by URL) and confirms subscriptions. */
@Component
public class HttpSnsCertificateProvider implements SnsCertificateProvider {

    private static final int MAX_BYTES = 16 * 1024;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<URI, PublicKey> cache = new ConcurrentHashMap<>();

    @Override
    public PublicKey publicKey(URI certUrl) {
        PublicKey cached = cache.get(certUrl);
        if (cached != null) return cached;
        byte[] body = get(certUrl);
        try {
            PublicKey key = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(body)).getPublicKey();
            if (cache.size() > 20) cache.clear();
            cache.put(certUrl, key);
            return key;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not read the SNS signing certificate", e);
        }
    }

    @Override
    public void confirmSubscription(URI subscribeUrl) {
        get(subscribeUrl);
    }

    private byte[] get(URI uri) {
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_BYTES) {
                throw new IllegalStateException("Unexpected answer (" + response.statusCode() + ") from " + uri.getHost());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach " + uri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}

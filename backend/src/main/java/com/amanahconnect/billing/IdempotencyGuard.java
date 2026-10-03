package com.amanahconnect.billing;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code Idempotency-Key} header on money-recording requests. The same key with the same request gives the original
 * result again instead of recording a second payment (a double click, a retry after a timeout); the same key with a
 * different request is refused. Concurrent requests with one key are serialised by an advisory lock, and a unique index
 * makes a second row impossible even if the lock were skipped.
 */
@Component
public class IdempotencyGuard {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final PaymentRecordRepository payments;
    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyGuard(PaymentRecordRepository payments, NamedParameterJdbcTemplate jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /** The key as given, or null if none was sent. */
    public static String validate(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (!KEY.matcher(key.trim()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be 8 to 100 letters, digits or . _ : -", List.of("Idempotency-Key: invalid"));
        }
        return key.trim();
    }

    /**
     * Takes the key's lock (held until the transaction ends) and looks the key up.
     *
     * @return the payment the key already produced, if the request is the same one
     * @throws ApiException 422 IDEMPOTENCY_KEY_REUSED if the key was used for a different request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentRecord> begin(UUID communityId, String key, String requestHash) {
        if (key == null) {
            return Optional.empty();
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))", new MapSqlParameterSource("k", "idem:" + communityId + ":" + key), rs -> { });
        Optional<PaymentRecord> existing = payments.findByCommunityIdAndIdempotencyKey(communityId, key);
        if (existing.isPresent() && !requestHash.equals(existing.get().getRequestHash())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used for a different request. Use a new key for a new payment.");
        }
        return existing;
    }

    /** A stable fingerprint of what a request asks for. */
    public static String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

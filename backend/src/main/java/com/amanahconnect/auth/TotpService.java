package com.amanahconnect.auth;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * RFC 6238 TOTP (SHA-1, 6 digits, 30 seconds, as every authenticator app expects).
 *
 * <p>Accepts the current step and one step either side to absorb clock drift, and reports which step
 * matched so the caller can refuse a code that was already used.
 */
@Service
public class TotpService {

    static final int PERIOD_SECONDS = 30;
    static final int DIGITS = 6;
    private static final String ISSUER = "Amanah Connect";
    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    private final SecretGenerator secrets = new DefaultSecretGenerator(32);
    private final CodeGenerator codes = new DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGITS);
    private final Clock clock;

    public TotpService(Clock clock) {
        this.clock = clock;
    }

    public String newSecret() {
        return secrets.generate();
    }

    public String otpAuthUri(String secret, String accountEmail) {
        return new QrData.Builder()
                .label(accountEmail)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(DIGITS)
                .period(PERIOD_SECONDS)
                .build()
                .getUri();
    }

    /**
     * @param lastUsedStep the last accepted step for this user, or null
     * @return the matched step, or empty if the code is wrong or is a replay
     */
    public OptionalLong verify(String secret, String code, Long lastUsedStep) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        long current = Math.floorDiv(clock.instant().getEpochSecond(), PERIOD_SECONDS);
        OptionalLong matched = OptionalLong.empty();
        // Check every candidate (no early exit) so timing does not reveal which step matched.
        for (long step = current - 1; step <= current + 1; step++) {
            if (Tokens.constantTimeEquals(generate(secret, step), code) && matched.isEmpty()) {
                matched = OptionalLong.of(step);
            }
        }
        if (matched.isPresent() && lastUsedStep != null && matched.getAsLong() <= lastUsedStep) {
            return OptionalLong.empty();
        }
        return matched;
    }

    /** The code an authenticator app would show at the given moment. Exposed for tests. */
    public String codeAt(String secret, java.time.Instant when) {
        return generate(secret, Math.floorDiv(when.getEpochSecond(), PERIOD_SECONDS));
    }

    private String generate(String secret, long step) {
        try {
            return codes.generate(secret, step);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException("Could not generate a TOTP code", e);
        }
    }
}

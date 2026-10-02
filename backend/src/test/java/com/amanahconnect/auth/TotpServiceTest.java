package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    /** RFC 6238 test secret ("12345678901234567890") in base32. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static TotpService at(long epochSeconds) {
        return new TotpService(Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC));
    }

    @Test
    void matchesTheRfc6238VectorForTheSha1SixDigitCase() {
        // RFC 6238 appendix B lists 94287082 for T=59; its last six digits are the 6-digit code.
        TotpService service = at(59);

        assertThat(service.codeAt(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(service.verify(RFC_SECRET, "287082", null)).isPresent();
    }

    @Test
    void acceptsOneStepOfDriftEitherWayButNotTwo() {
        TotpService service = at(1_000_000_000L);
        long now = 1_000_000_000L;

        assertThat(service.verify(RFC_SECRET, service.codeAt(RFC_SECRET, Instant.ofEpochSecond(now - 30)), null)).isPresent();
        assertThat(service.verify(RFC_SECRET, service.codeAt(RFC_SECRET, Instant.ofEpochSecond(now + 30)), null)).isPresent();
        assertThat(service.verify(RFC_SECRET, service.codeAt(RFC_SECRET, Instant.ofEpochSecond(now - 60)), null)).isEmpty();
        assertThat(service.verify(RFC_SECRET, service.codeAt(RFC_SECRET, Instant.ofEpochSecond(now + 60)), null)).isEmpty();
    }

    @Test
    void refusesAReplayOfTheSameOrAnEarlierStep() {
        TotpService service = at(1_000_000_000L);
        String code = service.codeAt(RFC_SECRET, Instant.ofEpochSecond(1_000_000_000L));

        OptionalLong first = service.verify(RFC_SECRET, code, null);
        assertThat(first).isPresent();

        assertThat(service.verify(RFC_SECRET, code, first.getAsLong())).as("same step").isEmpty();
        String earlier = service.codeAt(RFC_SECRET, Instant.ofEpochSecond(1_000_000_000L - 30));
        assertThat(service.verify(RFC_SECRET, earlier, first.getAsLong())).as("older step").isEmpty();
        String later = service.codeAt(RFC_SECRET, Instant.ofEpochSecond(1_000_000_000L + 30));
        assertThat(service.verify(RFC_SECRET, later, first.getAsLong())).as("a newer step is fine").isPresent();
    }

    @Test
    void rejectsMalformedCodes() {
        TotpService service = at(59);

        for (String bad : new String[] {null, "", "12345", "1234567", "abcdef", "287 082", "28708a"}) {
            assertThat(service.verify(RFC_SECRET, bad, null)).as("%s", bad).isEmpty();
        }
    }

    @Test
    void generatesDistinctBase32SecretsAndAnOtpAuthUri() {
        TotpService service = at(59);

        String a = service.newSecret();
        String b = service.newSecret();

        assertThat(a).matches("[A-Z2-7]{32}").isNotEqualTo(b);
        assertThat(service.otpAuthUri(a, "user@example.com"))
                .startsWith("otpauth://totp/")
                .contains("secret=" + a, "issuer=Amanah%20Connect", "algorithm=SHA1", "digits=6", "period=30");
    }
}

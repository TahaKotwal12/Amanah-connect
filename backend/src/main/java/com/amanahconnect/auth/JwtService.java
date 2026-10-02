package com.amanahconnect.auth;

import com.amanahconnect.auth.crypto.AuthKeys;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Issues and verifies the two kinds of signed token: the 15-minute access token and the 5-minute
 * MFA challenge token. The {@code typ} claim keeps them apart, so an MFA token can never be used as a
 * bearer token and an access token can never complete an MFA challenge.
 */
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_MFA = "mfa";
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_MFA_SETUP_REQUIRED = "mfa_setup_required";

    private final AuthProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder mfaDecoder;

    public JwtService(AuthProperties properties, AuthKeys keys, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        SecretKey key = keys.jwtKey();
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, TYPE_ACCESS);
        this.mfaDecoder = decoder(key, TYPE_MFA);
    }

    /** The decoder Spring Security uses to authenticate bearer tokens: access tokens only. */
    public JwtDecoder accessTokenDecoder() {
        return accessDecoder;
    }

    public String issueAccessToken(User user, boolean mfaSetupRequired) {
        Instant now = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .subject(user.getId().toString())
                        .issuedAt(now)
                        .expiresAt(now.plus(properties.accessTtl()))
                        .id(UUID.randomUUID().toString())
                        .claim(CLAIM_TYPE, TYPE_ACCESS)
                        .claim(CLAIM_ROLE, user.getRole().name())
                        .claim(CLAIM_MFA_SETUP_REQUIRED, mfaSetupRequired)
                        .build();
        return encode(claims);
    }

    public String issueMfaToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .subject(user.getId().toString())
                        .issuedAt(now)
                        .expiresAt(now.plus(properties.mfaTtl()))
                        .id(UUID.randomUUID().toString())
                        .claim(CLAIM_TYPE, TYPE_MFA)
                        .build();
        return encode(claims);
    }

    /** @return the user id the MFA challenge was issued for */
    public UUID parseMfaToken(String token) {
        try {
            return UUID.fromString(mfaDecoder.decode(token).getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "The verification session is invalid or has expired. Sign in again.");
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder decoder(SecretKey key, String expectedType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> typeValidator =
                jwt ->
                        expectedType.equals(jwt.getClaimAsString(CLAIM_TYPE))
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Wrong token type", null));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(properties.issuer()), typeValidator));
        return decoder;
    }
}

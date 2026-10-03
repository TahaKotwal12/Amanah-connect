package com.amanahconnect.billing;

import com.amanahconnect.auth.AuthProperties;
import com.amanahconnect.auth.Tokens;
import com.amanahconnect.billing.BillingDtos.PayLinkView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment links: unguessable (256 random bits), expiring, stored hashed, one per bill email. A link opens the public
 * payment-info page of exactly one invoice and nothing else.
 */
@Service
@Transactional
public class PayLinkService {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final PaymentLinkRepository links;
    private final BillingProperties properties;
    private final AuthProperties authProperties;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PayLinkService(PaymentLinkRepository links, BillingProperties properties, AuthProperties authProperties, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.links = links;
        this.properties = properties;
        this.authProperties = authProperties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public PayLinkView create(UUID communityId, UUID invoiceId, UUID createdBy) {
        String token = Tokens.newOpaqueToken();
        PaymentLink link = new PaymentLink();
        link.setCommunityId(communityId);
        link.setInvoiceId(invoiceId);
        link.setTokenHash(Tokens.sha256Hex(token));
        link.setExpiresAt(clock.instant().plus(Duration.ofDays(properties.payLinkDays())));
        link.setCreatedBy(createdBy);
        links.save(link);
        return new PayLinkView(authProperties.frontendBaseUrl().replaceAll("/+$", "") + "/pay/" + token, link.getExpiresAt());
    }

    /** The live link for a token, or null: unknown, malformed, revoked and expired are all the same "no". */
    @Transactional(readOnly = true)
    public PaymentLink resolve(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return null;
        }
        Instant now = clock.instant();
        return links.findByTokenHash(Tokens.sha256Hex(token)).filter(l -> l.getRevokedAt() == null && l.getExpiresAt().isAfter(now)).orElse(null);
    }

    /** Stops every link of an invoice (when it is cancelled). */
    public void revokeAll(UUID communityId, UUID invoiceId) {
        jdbc.update("UPDATE payment_links SET revoked_at = now() WHERE community_id = :c AND invoice_id = :i AND revoked_at IS NULL",
                new MapSqlParameterSource("c", communityId).addValue("i", invoiceId));
    }
}

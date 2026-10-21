package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminInviteIT extends AbstractAdminIT {

    private static final String INVITES = ADMIN + "/invites";

    private UUID ownerOf(UUID communityId) {
        return jdbc.queryForObject("select user_id from community_users where community_id = ? and role = 'OWNER'", UUID.class, communityId);
    }

    private JsonNode row(UUID userId) {
        ApiClient.Response response = adminGet(INVITES + "?size=100&sort=invitedAt,desc");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode item : response.json().get("items")) {
            if (userId.toString().equals(item.get("userId").asString())) {
                return item;
            }
        }
        return null;
    }

    private long activeTokens(UUID userId) {
        return jdbc.queryForObject("select count(*) from auth_tokens where user_id = ? and purpose = 'INVITATION' and used_at is null", Long.class, userId);
    }

    @Test
    void aNewCommunityOwnerShowsAsPendingWithTheCommunity() {
        UUID community = createCommunityViaApi("Invite Pending");
        UUID owner = ownerOf(community);

        JsonNode row = row(owner);

        assertThat(row).isNotNull();
        assertThat(row.get("status").asString()).isEqualTo("PENDING");
        assertThat(row.get("communityName").asString()).isEqualTo("Invite Pending");
        assertThat(row.get("acceptedAt").isNull()).isTrue();
    }

    @Test
    void anUnusedInvitationPastItsExpiryIsExpired() {
        UUID owner = ownerOf(createCommunityViaApi("Invite Expired"));
        jdbc.update("update auth_tokens set expires_at = now() - interval '1 day' where user_id = ? and purpose = 'INVITATION'", owner);

        assertThat(row(owner).get("status").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void resendingIssuesOneNewLinkAndCancelsTheOld() {
        UUID owner = ownerOf(createCommunityViaApi("Invite Resend"));
        jdbc.update("update auth_tokens set expires_at = now() - interval '1 day' where user_id = ? and purpose = 'INVITATION'", owner);

        ApiClient.Response response = adminPost(INVITES + "/" + owner + "/resend", java.util.Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(activeTokens(owner)).isEqualTo(1);
        assertThat(row(owner).get("status").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'INVITATION_CREATED' and entity_id = ?", Long.class, owner)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void revokingStopsTheLinkIsAuditedAndCannotBeDoneTwice() {
        UUID community = createCommunityViaApi("Invite Revoke");
        UUID owner = ownerOf(community);

        ApiClient.Response revoked = adminPost(INVITES + "/" + owner + "/revoke", java.util.Map.of());

        assertThat(revoked.status()).as(revoked.body()).isEqualTo(204);
        assertThat(activeTokens(owner)).isZero();
        assertThat(row(owner).get("status").asString()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'INVITATION_REVOKED' and community_id = ?", Long.class, community)).isEqualTo(1);

        ApiClient.Response again = adminPost(INVITES + "/" + owner + "/revoke", java.util.Map.of());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");

        // A revoked invitation can be sent again.
        assertThat(adminPost(INVITES + "/" + owner + "/resend", java.util.Map.of()).status()).isEqualTo(202);
        assertThat(row(owner).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void anActiveAccountIsAcceptedAndCannotBeRevokedOrResent() {
        UUID owner = ownerOf(createCommunityViaApi("Invite Accepted"));
        jdbc.update("update users set status = 'ACTIVE' where id = ?", owner);
        jdbc.update("update auth_tokens set used_at = now() where user_id = ? and purpose = 'INVITATION'", owner);

        JsonNode row = row(owner);
        assertThat(row.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(row.get("acceptedAt").isNull()).isFalse();
        assertThat(adminPost(INVITES + "/" + owner + "/revoke", java.util.Map.of()).status()).isEqualTo(409);
        assertThat(adminPost(INVITES + "/" + owner + "/resend", java.util.Map.of()).status()).isEqualTo(409);
    }

    @Test
    void filtersByStatusAndSearchAndRejectsABadStatus() {
        UUID owner = ownerOf(createCommunityViaApi("Findable Invite Co"));

        ApiClient.Response pending = adminGet(INVITES + "?status=PENDING&q=findable invite");
        assertThat(pending.json().get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(pending.json().get("items").get(0).get("userId").asString()).isEqualTo(owner.toString());

        assertThat(adminGet(INVITES + "?status=ACCEPTED&q=findable invite").json().get("total").asLong()).isZero();
        assertThat(adminGet(INVITES + "?status=NOPE").status()).isEqualTo(400);
    }

    @Test
    void anUnknownUserIs404() {
        assertThat(adminPost(INVITES + "/" + UUID.randomUUID() + "/resend", java.util.Map.of()).status()).isEqualTo(404);
        assertThat(adminPost(INVITES + "/" + UUID.randomUUID() + "/revoke", java.util.Map.of()).status()).isEqualTo(404);
    }
}

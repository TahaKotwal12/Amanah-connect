package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MemberCommunicationsIT extends AbstractTenantIT {

    private void queue(UUID communityId, String to, String template, String status) {
        jdbc.update(
                "insert into email_outbox (community_id, to_email, template, status, sent_at) values (?, ?, ?, ?, case when ? = 'SENT' then now() end)",
                communityId, to, template, status, status);
    }

    private String path(UUID memberId) {
        return "/api/v1/community/members/" + memberId + "/communications";
    }

    @Test
    void listsTheEmailsSentToTheMembersAddressNewestFirst() {
        var member = data.member(communityA);
        String email = member.getEmail();
        queue(communityA.getId(), email, "member-welcome", "SENT");
        queue(communityA.getId(), email.toUpperCase(), "invoice-reminder", "FAILED");
        queue(communityA.getId(), "someone.else@example.test", "member-welcome", "SENT");

        ApiClient.Response response = asA("GET", path(member.getId()), null);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = response.json();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("template").asString()).isEqualTo("invoice-reminder");
        assertThat(items.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(items.get(1).get("sentAt").isNull()).isFalse();
    }

    @Test
    void aMemberWithNoEmailHasNoCommunications() {
        var member = data.member(communityA);
        jdbc.update("update members set email = null where id = ?", member.getId());

        assertThat(asA("GET", path(member.getId()), null).json()).isEmpty();
    }

    @Test
    void anotherCommunitysEmailsToTheSameAddressAreNotShown() {
        var member = data.member(communityA);
        queue(communityB.getId(), member.getEmail(), "member-welcome", "SENT");

        assertThat(asA("GET", path(member.getId()), null).json()).isEmpty();
    }

    @Test
    void isHiddenFromAnotherCommunity() {
        var memberOfB = data.member(communityB);
        queue(communityB.getId(), memberOfB.getEmail(), "member-welcome", "SENT");

        assertCrossTenantRead(path(memberOfB.getId()));
    }
}

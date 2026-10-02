package com.amanahconnect.common.page;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaginationIT extends AbstractTenantIT {

    private static final String LIST = "/api/v1/community/test-support/members";

    private void member(String name) {
        var member = data.member(communityA);
        jdbc.update("update members set full_name = ? where id = ?", name, member.getId());
    }

    private List<String> names(ApiClient.Response response) {
        return response.json().get("items").valueStream().map(n -> n.get("fullName").asString()).toList();
    }

    @Test
    void returnsTheStandardEnvelopeWithDefaults() {
        member("Alpha");
        member("Bravo");

        ApiClient.Response response = asA("GET", LIST, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(response.json().get("total").asInt()).isEqualTo(2);
        assertThat(response.json().get("page").asInt()).isZero();
        assertThat(response.json().get("size").asInt()).isEqualTo(20);
        assertThat(response.json().get("items").size()).isEqualTo(2);
    }

    @Test
    void itemsAreDtosNotEntities() {
        member("Alpha");

        var item = asA("GET", LIST, null).json().get("items").get(0);

        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "memberNo", "fullName");
    }

    @Test
    void pagesThroughResultsWithoutRepeatsEvenWhenTheSortedValueIsIdentical() {
        for (int i = 0; i < 7; i++) {
            member("Same Name");
        }
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();

        for (int page = 0; page < 4; page++) {
            ApiClient.Response response = asA("GET", LIST + "?size=2&sort=name&page=" + page, null);
            assertThat(response.json().get("total").asInt()).isEqualTo(7);
            response.json().get("items").valueStream().forEach(n -> {
                order.add(n.get("id").asString());
                seen.add(n.get("id").asString());
            });
        }

        assertThat(order).hasSize(7);
        assertThat(seen).as("the id tiebreaker keeps page boundaries stable").hasSize(7);
        assertThat(asA("GET", LIST + "?size=2&page=4", null).json().get("items").size()).as("past the end").isZero();
    }

    @Test
    void sortsByWhitelistedFieldsInEitherDirection() {
        member("Charlie");
        member("Alpha");
        member("Bravo");

        assertThat(names(asA("GET", LIST + "?sort=name", null))).containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(names(asA("GET", LIST + "?sort=name,desc", null))).containsExactly("Charlie", "Bravo", "Alpha");
        assertThat(names(asA("GET", LIST + "?sort=name&sort=desc", null))).containsExactly("Charlie", "Bravo", "Alpha");
    }

    @Test
    void rejectsSizesOverTheMaximumInsteadOfSilentlyClamping() {
        ApiClient.Response response = asA("GET", LIST + "?size=101", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").toString()).contains("size").contains("at most 100");
        assertThat(asA("GET", LIST + "?size=100", null).status()).isEqualTo(200);
    }

    @Test
    void rejectsNegativePagesAndZeroSizes() {
        assertThat(asA("GET", LIST + "?page=-1", null).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(asA("GET", LIST + "?size=0", null).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void rejectsNonNumericValuesWithoutLeakingJavaTypes() {
        ApiClient.Response response = asA("GET", LIST + "?page=abc", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body()).doesNotContain("java.lang").doesNotContain("Integer").doesNotContain("NumberFormat");
    }

    @Test
    void rejectsSortFieldsThatAreNotOnTheWhitelist() {
        ApiClient.Response response = asA("GET", LIST + "?sort=deletedAt", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").toString()).contains("not sortable").contains("name", "number", "created");
    }

    @Test
    void totalsOnlyCountTheCallersOwnCommunity() {
        member("Mine");
        data.member(communityB);
        data.member(communityB);

        assertThat(asA("GET", LIST, null).json().get("total").asInt()).isEqualTo(1);
        assertThat(asB("GET", LIST, null).json().get("total").asInt()).isEqualTo(2);
    }
}

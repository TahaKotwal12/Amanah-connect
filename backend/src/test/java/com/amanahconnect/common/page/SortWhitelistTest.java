package com.amanahconnect.common.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;

class SortWhitelistTest {

    private final SortWhitelist whitelist =
            SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("name", "fullName", "number", "memberNo", "created", "createdAt"));

    private static PageQuery query(Integer page, Integer size, String... sort) {
        return new PageQuery(page, size, sort.length == 0 ? null : List.of(sort));
    }

    private static List<String> orders(PageRequest request) {
        return request.getSort().stream().map(o -> o.getProperty() + " " + o.getDirection()).toList();
    }

    @Test
    void usesDefaultsAndAddsAStableTiebreaker() {
        PageRequest request = whitelist.toPageRequest(query(null, null));

        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(20);
        assertThat(orders(request)).containsExactly("createdAt DESC", "id ASC");
    }

    @Test
    void parsesTheCommaFormTheSeparateFormAndMultipleFields() {
        assertThat(orders(whitelist.toPageRequest(query(0, 10, "name", "desc")))).containsExactly("fullName DESC", "id ASC");
        assertThat(orders(whitelist.toPageRequest(query(0, 10, "name")))).containsExactly("fullName ASC", "id ASC");
        assertThat(orders(whitelist.toPageRequest(query(0, 10, "name", "DESC", "number", "asc")))).containsExactly("fullName DESC", "memberNo ASC", "id ASC");
        assertThat(orders(whitelist.toPageRequest(query(0, 10, " name ")))).containsExactly("fullName ASC", "id ASC");
    }

    @Test
    void mapsApiNamesToEntityPropertiesAndNeverExposesTheProperty() {
        assertThat(orders(whitelist.toPageRequest(query(0, 10, "created", "asc")))).containsExactly("createdAt ASC", "id ASC");
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "fullName"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "createdAt"))).isInstanceOf(ApiException.class);
    }

    @Test
    void doesNotAddASecondIdOrderWhenIdIsAlreadySorted() {
        SortWhitelist withId = SortWhitelist.of(Sort.by("id"), "id", "name");

        assertThat(orders(withId.toPageRequest(query(0, 5, "id", "desc")))).containsExactly("id DESC");
    }

    @Test
    void rejectsUnknownFieldsListingTheAllowedOnes() {
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "password")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).singleElement().asString().contains("'password' is not sortable").contains("name", "number", "created");
                });
    }

    @Test
    void rejectsInjectionAttemptsAndNeverEchoesThemRaw() {
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "name; drop table members--")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().toString()).doesNotContain("drop table").contains("'name??drop?table?members--'"));
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "x".repeat(500))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().toString().length()).isLessThan(300));
    }

    @Test
    void rejectsDanglingDirectionsAndDuplicates() {
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "desc"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 10, "name", "asc", "name", "desc"))).isInstanceOf(ApiException.class);
    }

    @Test
    void enforcesPageAndSizeBounds() {
        assertThat(whitelist.toPageRequest(query(0, 100)).getPageSize()).isEqualTo(100);
        assertThat(whitelist.toPageRequest(query(0, 1)).getPageSize()).isEqualTo(1);
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 101))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> whitelist.toPageRequest(query(0, 0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> whitelist.toPageRequest(query(-1, 10))).isInstanceOf(ApiException.class);
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> whitelist.toPageRequest(query(-1, 500, "nope")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).hasSize(3));
    }

    @Test
    void pageResponseMapsEntitiesToDtosAndHasExactlyTheDocumentedShape() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(2, 2), 42);

        PageResponse<Integer> response = PageResponse.from(page, String::length);
        String body = JsonMapper.builder().build().writeValueAsString(response);

        assertThat(body).isEqualTo("{\"items\":[1,1],\"total\":42,\"page\":2,\"size\":2}");
    }
}

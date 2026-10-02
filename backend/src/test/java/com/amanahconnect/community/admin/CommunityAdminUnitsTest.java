package com.amanahconnect.community.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.community.CommunityStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommunityAdminUnitsTest {

    @Test
    void suspendIsAllowedFromActiveAndPendingOnly() {
        assertThat(CommunityStatusMachine.suspend(CommunityStatus.ACTIVE)).isEqualTo(CommunityStatus.SUSPENDED);
        assertThat(CommunityStatusMachine.suspend(CommunityStatus.PENDING)).isEqualTo(CommunityStatus.SUSPENDED);
        for (CommunityStatus from : new CommunityStatus[] {CommunityStatus.SUSPENDED, CommunityStatus.ARCHIVED}) {
            assertThatThrownBy(() -> CommunityStatusMachine.suspend(from)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        }
    }

    @Test
    void activateWorksFromEveryStatusExceptActiveIncludingRestoringAnArchivedCommunity() {
        for (CommunityStatus from : new CommunityStatus[] {CommunityStatus.PENDING, CommunityStatus.SUSPENDED, CommunityStatus.ARCHIVED}) {
            assertThat(CommunityStatusMachine.activate(from)).isEqualTo(CommunityStatus.ACTIVE);
        }
        assertThatThrownBy(() -> CommunityStatusMachine.activate(CommunityStatus.ACTIVE)).isInstanceOf(ApiException.class);
    }

    @Test
    void archiveWorksFromEveryStatusExceptArchived() {
        for (CommunityStatus from : new CommunityStatus[] {CommunityStatus.PENDING, CommunityStatus.ACTIVE, CommunityStatus.SUSPENDED}) {
            assertThat(CommunityStatusMachine.archive(from)).isEqualTo(CommunityStatus.ARCHIVED);
        }
        assertThatThrownBy(() -> CommunityStatusMachine.archive(CommunityStatus.ARCHIVED)).isInstanceOf(ApiException.class);
    }

    @Test
    void slugsAreLowerCaseAsciiWithHyphens() {
        assertThat(SlugGenerator.base("Garden Society")).isEqualTo("garden-society");
        assertThat(SlugGenerator.base("  Café & Friends!! ")).isEqualTo("cafe-friends");
        assertThat(SlugGenerator.base("---")).as("nothing usable").isEqualTo("community");
        assertThat(SlugGenerator.base("नमस्ते")).as("non-latin names fall back").isEqualTo("community");
        assertThat(SlugGenerator.base("a".repeat(200))).hasSizeLessThanOrEqualTo(70);
        assertThat(SlugGenerator.base("Word ".repeat(40))).doesNotEndWith("-");
        assertThat(SlugGenerator.VALID.matcher(SlugGenerator.base("Any Name 123")).matches()).isTrue();
    }

    @Test
    void uniqueSlugsGetANumericSuffix() {
        Set<String> taken = Set.of("lotus", "lotus-2", "lotus-3");
        assertThat(SlugGenerator.unique("Lotus", taken::contains)).isEqualTo("lotus-4");
        assertThat(SlugGenerator.unique("Other", taken::contains)).isEqualTo("other");
    }
}

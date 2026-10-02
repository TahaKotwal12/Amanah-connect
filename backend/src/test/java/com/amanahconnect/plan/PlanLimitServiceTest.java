package com.amanahconnect.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.notification.EmailStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlanLimitServiceTest {

    private static final UUID COMMUNITY = UUID.randomUUID();

    private final CommunityRepository communities = mock(CommunityRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmailOutboxRepository outbox = mock(EmailOutboxRepository.class);
    private final StorageUsageProvider storage = mock(StorageUsageProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-17T10:15:30Z"), ZoneOffset.UTC);
    private final PlanLimitService service = new PlanLimitService(communities, members, outbox, storage, clock);

    private void plan(Map<String, Object> limits, Map<String, Object> features) {
        Plan plan = new Plan();
        plan.setCode("STARTER");
        plan.setName("Starter");
        plan.setLimits(limits);
        plan.setFeatures(features);
        Community community = new Community();
        community.setPlan(plan);
        when(communities.findById(COMMUNITY)).thenReturn(Optional.of(community));
    }

    private static Map<String, Object> limit(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @BeforeEach
    void defaults() {
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(0L);
    }

    // ---- members ----------------------------------------------------------------------------

    @Test
    void allowsAMemberBelowTheLimit() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, 100), Map.of());
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(99L);

        assertThatCode(() -> service.checkMemberLimit(COMMUNITY)).doesNotThrowAnyException();
    }

    @Test
    void refusesTheMemberThatWouldExceedTheLimitWithAMessageNamingLimitAndPlan() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, 100), Map.of());
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(100L);

        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY))
                .isInstanceOfSatisfying(PlanLimitExceededException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(e.code().status().value()).isEqualTo(402);
                    assertThat(e.getMessage()).contains("Starter plan", "100 members", "Upgrade");
                    assertThat(e.properties()).containsEntry("limit", "max_members").containsEntry("limitValue", 100L)
                            .containsEntry("current", 100L).containsEntry("plan", "STARTER");
                });
    }

    @Test
    void checksABulkImportAgainstTheRemainingRoom() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, 100), Map.of());
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(90L);

        assertThatCode(() -> service.checkMemberLimit(COMMUNITY, 10)).as("exactly fills the plan").doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY, 11)).isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    void aNullOrMissingLimitMeansUnlimited() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, null), Map.of());
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(1_000_000L);
        assertThatCode(() -> service.checkMemberLimit(COMMUNITY, 50_000)).doesNotThrowAnyException();

        plan(Map.of(), Map.of());
        assertThatCode(() -> service.checkMemberLimit(COMMUNITY)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkStorage(COMMUNITY, Long.MAX_VALUE / 2)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkEmailQuota(COMMUNITY, 1_000_000)).doesNotThrowAnyException();
    }

    @Test
    void acceptsLimitsStoredAsAnyJsonNumberType() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, 5L), Map.of());
        when(members.countByCommunityIdAndDeletedAtIsNull(COMMUNITY)).thenReturn(5L);
        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY)).isInstanceOf(PlanLimitExceededException.class);

        plan(limit(PlanLimitKeys.MAX_MEMBERS, 5.0d), Map.of());
        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY)).isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    void aCorruptLimitFailsLoudlyInsteadOfSilentlyAllowingEverything() {
        plan(limit(PlanLimitKeys.MAX_MEMBERS, "lots"), Map.of());

        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY)).isInstanceOf(IllegalStateException.class).hasMessageContaining("max_members");
    }

    // ---- storage ----------------------------------------------------------------------------

    @Test
    void storageIsComparedInMegabytes() {
        plan(limit(PlanLimitKeys.STORAGE_MB, 500), Map.of());
        long mb = 1024L * 1024L;
        when(storage.usedBytes(COMMUNITY)).thenReturn(499L * mb);

        assertThatCode(() -> service.checkStorage(COMMUNITY, mb)).as("exactly 500 MB").doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkStorage(COMMUNITY, mb + 1))
                .isInstanceOfSatisfying(PlanLimitExceededException.class, e -> {
                    assertThat(e.getMessage()).contains("Starter plan", "500 MB");
                    assertThat(e.properties()).containsEntry("limit", "storage_mb").containsEntry("limitValue", 500L).containsEntry("current", 499L);
                });
    }

    // ---- emails -----------------------------------------------------------------------------

    @Test
    void emailQuotaCountsTheCurrentCalendarMonthExcludingFailedMessages() {
        plan(limit(PlanLimitKeys.EMAILS_PER_MONTH, 500), Map.of());
        when(outbox.countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(eq(COMMUNITY), any(), eq(EmailStatus.FAILED))).thenReturn(499L);

        assertThatCode(() -> service.checkEmailQuota(COMMUNITY)).doesNotThrowAnyException();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(eq(COMMUNITY), since.capture(), eq(EmailStatus.FAILED));
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void emailQuotaRefusesWhenTheBatchWouldExceedIt() {
        plan(limit(PlanLimitKeys.EMAILS_PER_MONTH, 500), Map.of());
        when(outbox.countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(eq(COMMUNITY), any(), any())).thenReturn(495L);

        assertThatCode(() -> service.checkEmailQuota(COMMUNITY, 5)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkEmailQuota(COMMUNITY, 6))
                .isInstanceOfSatisfying(PlanLimitExceededException.class, e -> {
                    assertThat(e.getMessage()).contains("Starter plan", "500 emails per month", "495");
                    assertThat(e.properties()).containsEntry("limit", "emails_per_month");
                });
    }

    // ---- features ---------------------------------------------------------------------------

    @Test
    void requireFeatureAllowsOnlyAnExplicitTrue() {
        plan(Map.of(), Map.of("pdf_reports", true, "csv_export", false, "weird", "yes"));

        assertThatCode(() -> service.requireFeature(COMMUNITY, "pdf_reports")).doesNotThrowAnyException();
        for (String feature : new String[] {"csv_export", "weird", "not_listed"}) {
            assertThatThrownBy(() -> service.requireFeature(COMMUNITY, feature)).as(feature).isInstanceOf(PlanFeatureUnavailableException.class);
        }
    }

    @Test
    void anUnavailableFeatureIsA402WithAReadableMessage() {
        plan(Map.of(), Map.of("pdf_reports", false));

        assertThatThrownBy(() -> service.requireFeature(COMMUNITY, "pdf_reports"))
                .isInstanceOfSatisfying(PlanFeatureUnavailableException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLAN_FEATURE_UNAVAILABLE);
                    assertThat(e.code().status().value()).isEqualTo(402);
                    assertThat(e.getMessage()).contains("Starter plan", "pdf reports", "Upgrade");
                    assertThat(e.properties()).containsEntry("feature", "pdf_reports").containsEntry("plan", "STARTER");
                });
    }

    // ---- unknown community ------------------------------------------------------------------

    @Test
    void anUnknownOrHiddenCommunityIsNotFound() {
        when(communities.findById(COMMUNITY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkMemberLimit(COMMUNITY)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.requireFeature(COMMUNITY, "pdf_reports")).isInstanceOf(NotFoundException.class);
    }
}

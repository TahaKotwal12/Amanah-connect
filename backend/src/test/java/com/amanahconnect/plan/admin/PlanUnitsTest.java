package com.amanahconnect.plan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.plan.SubscriptionStatusRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanUnitsTest {

    @Test
    void acceptsKnownLimitsAndBooleanFeatures() {
        Map<String, Object> limits = new HashMap<>();
        limits.put("max_members", 100);
        limits.put("storage_mb", null); // unlimited
        limits.put("emails_per_month", 5000L);
        assertThatCode(() -> PlanDefinitionValidator.validate(limits, Map.of("bulk_email", true, "exports", false))).doesNotThrowAnyException();
        assertThatCode(() -> PlanDefinitionValidator.validate(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> PlanDefinitionValidator.validate(Map.of("max_members", new BigDecimal("50")), Map.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownNegativeFractionalAndNonNumericLimits() {
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(Map.of("seats", 5), null)).isInstanceOf(ApiException.class).hasMessageContaining("invalid");
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(Map.of("max_members", -1), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(Map.of("max_members", 1.5), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(Map.of("max_members", "100"), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsBadFeatureNamesAndValues() {
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(null, Map.of("Bulk Email", true))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(null, Map.of("bulk_email", "yes"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlanDefinitionValidator.validate(null, Map.of("bulk_email", 1))).isInstanceOf(ApiException.class);
    }

    @Test
    void reportsEveryProblemAtOnceWithoutEchoingHostileKeys() {
        ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> PlanDefinitionValidator.validate(Map.of("<script>alert(1)</script>", 1, "max_members", -5), Map.of("x y", true)));
        assertThat(e.details()).hasSize(3);
        assertThat(String.join("|", e.details())).doesNotContain("<script>");
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @Test
    void subscriptionDisplayStatus() {
        assertThat(SubscriptionStatusRules.display("CANCELLED", TODAY.plusDays(100), TODAY, 30, false)).isEqualTo("CANCELLED");
        assertThat(SubscriptionStatusRules.display("EXPIRED", TODAY.plusDays(100), TODAY, 30, false)).isEqualTo("EXPIRED");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY.minusDays(1), TODAY, 30, false)).as("lapsed before the job ran").isEqualTo("EXPIRED");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY, TODAY, 30, false)).as("last day still counts").isEqualTo("EXPIRING");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY.plusDays(30), TODAY, 30, false)).as("window edge").isEqualTo("EXPIRING");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY.plusDays(31), TODAY, 30, false)).isEqualTo("ACTIVE");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY.plusDays(5), TODAY, 30, true)).as("renewed").isEqualTo("ACTIVE");
        assertThat(SubscriptionStatusRules.display("ACTIVE", TODAY.minusDays(5), TODAY, 30, true)).as("renewal does not un-expire the past").isEqualTo("EXPIRED");
    }
}

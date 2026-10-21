package com.amanahconnect.plan;

import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.MemberRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a community administrator may see about their own plan: its limits, how much is used, and
 * where the subscription stands. Read only; changing the plan is the platform's job.
 */
@Service
@Transactional(readOnly = true)
public class CommunityPlanService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record SubscriptionState(String status, LocalDate periodStart, LocalDate periodEnd, long daysLeft) {}

    public record Usage(long members, long emailsThisMonth) {}

    /**
     * @param communityStatus PENDING, ACTIVE, SUSPENDED or ARCHIVED: a suspended community is read only
     * @param subscription the latest paid period that is not cancelled, or null when nothing was ever recorded
     * @param expiringWindowDays how many days before the end the subscription counts as expiring
     */
    public record CommunityPlanView(
            String planCode,
            String planName,
            Map<String, Object> limits,
            Map<String, Object> features,
            Usage usage,
            String communityStatus,
            SubscriptionState subscription,
            int expiringWindowDays) {}

    private final CommunityRepository communities;
    private final MemberRepository members;
    private final PlanLimitService limits;
    private final NamedParameterJdbcTemplate jdbc;
    private final SubscriptionProperties properties;
    private final Clock clock;

    public CommunityPlanService(
            CommunityRepository communities,
            MemberRepository members,
            PlanLimitService limits,
            NamedParameterJdbcTemplate jdbc,
            SubscriptionProperties properties,
            Clock clock) {
        this.communities = communities;
        this.members = members;
        this.limits = limits;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public CommunityPlanView get(UUID communityId) {
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        PlanSnapshot plan = limits.planOf(communityId);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        int window = properties.expiringWindowDays();
        return new CommunityPlanView(
                plan.code(),
                plan.name(),
                plan.limits(),
                plan.features(),
                new Usage(members.countByCommunityIdAndDeletedAtIsNull(communityId), limits.emailUsage(communityId).usedThisMonth()),
                community.getStatus().name(),
                subscription(communityId, today, window),
                window);
    }

    private SubscriptionState subscription(UUID communityId, LocalDate today, int window) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", communityId);
        List<Object[]> rows = jdbc.query(
                "SELECT s.status, s.period_start, s.period_end,"
                        + " EXISTS (SELECT 1 FROM platform_subscriptions n WHERE n.community_id = s.community_id"
                        + "   AND n.status = 'ACTIVE' AND n.period_end > s.period_end) AS renewed"
                        + " FROM platform_subscriptions s WHERE s.community_id = :id AND s.status <> 'CANCELLED'"
                        + " ORDER BY s.period_end DESC, s.created_at DESC LIMIT 1",
                params,
                (rs, i) -> new Object[] {rs.getString("status"), rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(), rs.getBoolean("renewed")});
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        LocalDate end = (LocalDate) row[2];
        String status = SubscriptionStatusRules.display((String) row[0], end, today, window, (Boolean) row[3]);
        return new SubscriptionState(status, (LocalDate) row[1], end, ChronoUnit.DAYS.between(today, end));
    }
}

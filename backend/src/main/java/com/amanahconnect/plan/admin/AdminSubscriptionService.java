package com.amanahconnect.plan.admin;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanRepository;
import com.amanahconnect.plan.PlatformSubscription;
import com.amanahconnect.plan.PlatformSubscriptionRepository;
import com.amanahconnect.plan.SubscriptionProperties;
import com.amanahconnect.plan.SubscriptionStatus;
import com.amanahconnect.plan.SubscriptionStatusRules;
import com.amanahconnect.plan.admin.AdminPlanDtos.RecordSubscriptionRequest;
import com.amanahconnect.plan.admin.AdminPlanDtos.SubscriptionView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform subscriptions: what communities pay Amanah Connect. Payments are recorded manually. A record is
 * never deleted; a mistaken one is cancelled with a reason.
 */
@Service
@Transactional
public class AdminSubscriptionService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Map<String, String> SORT_SQL =
            Map.of(
                    "periodEnd", "t.period_end",
                    "periodStart", "t.period_start",
                    "paidOn", "t.paid_on",
                    "amount", "t.amount",
                    "createdAt", "t.created_at",
                    "communityName", "lower(t.community_name)");

    private final PlatformSubscriptionRepository subscriptions;
    private final CommunityRepository communities;
    private final PlanRepository plans;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final SubscriptionProperties properties;
    private final Clock clock;
    private final jakarta.persistence.EntityManager em;

    public AdminSubscriptionService(
            PlatformSubscriptionRepository subscriptions,
            CommunityRepository communities,
            PlanRepository plans,
            AuditService audit,
            NamedParameterJdbcTemplate jdbc,
            SubscriptionProperties properties,
            Clock clock,
            jakarta.persistence.EntityManager em) {
        this.subscriptions = subscriptions;
        this.communities = communities;
        this.plans = plans;
        this.audit = audit;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.em = em;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    public SubscriptionView record(RecordSubscriptionRequest request) {
        Community community = communities.findById(request.communityId()).orElseThrow(() -> invalid("communityId", "unknown community"));
        Plan plan = plans.findById(request.planId()).orElseThrow(() -> invalid("planId", "unknown plan"));
        if (!plan.isActive()) {
            throw new ApiException(ErrorCode.PLAN_INACTIVE, "The " + plan.getName() + " plan is no longer offered. Choose an active plan.");
        }
        List<String> problems = new ArrayList<>();
        if (!request.periodEnd().isAfter(request.periodStart())) {
            problems.add("periodEnd: must be after periodStart");
        }
        LocalDate today = today();
        LocalDate paidOn = request.paidOn() == null ? today : request.paidOn();
        if (paidOn.isAfter(today)) {
            problems.add("paidOn: cannot be in the future");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", problems);
        }
        String reference = request.reference() == null || request.reference().isBlank() ? null : request.reference().trim();
        if (reference != null && subscriptions.existsByCommunityIdAndReferenceIgnoreCaseAndStatusNot(community.getId(), reference, SubscriptionStatus.CANCELLED)) {
            throw new ApiException(ErrorCode.DUPLICATE_REFERENCE, "This payment reference is already recorded for the community.");
        }
        PlatformSubscription latestBefore = subscriptions.findByCommunityIdOrderByPeriodEndDesc(community.getId()).stream()
                .filter(s -> s.getStatus() != SubscriptionStatus.CANCELLED).findFirst().orElse(null);

        PlatformSubscription subscription = new PlatformSubscription();
        subscription.setCommunityId(community.getId());
        subscription.setPlan(plan);
        subscription.setPeriodStart(request.periodStart());
        subscription.setPeriodEnd(request.periodEnd());
        subscription.setAmount(Money.of(request.amount()).amount());
        subscription.setReference(reference);
        subscription.setPaidOn(paidOn);
        subscription.setRecordedBy(AuditService.currentActorId());
        if (request.periodEnd().isBefore(today)) {
            // A back-dated record (history being entered): already over, so the job must not announce it.
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            subscription.setExpiredNotifiedAt(clock.instant());
        } else {
            subscription.setStatus(SubscriptionStatus.ACTIVE);
        }
        try {
            subscriptions.save(subscription);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.DUPLICATE_REFERENCE, "This payment reference is already recorded for the community.");
        }
        // The community runs on the plan of its latest subscription.
        if (latestBefore == null || !request.periodEnd().isBefore(latestBefore.getPeriodEnd())) {
            community.setPlan(plan);
        }
        audit.recordForCommunity("SUBSCRIPTION_RECORDED", community.getId(), "PlatformSubscription", subscription.getId(), null, snapshot(subscription));
        em.flush();
        return get(subscription.getId());
    }

    public SubscriptionView cancel(UUID id, String reason) {
        PlatformSubscription subscription = findEntity(id);
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This subscription is already cancelled.");
        }
        Map<String, Object> before = snapshot(subscription);
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelReason(reason.trim());
        Map<String, Object> after = snapshot(subscription);
        after.put("reason", reason.trim());
        audit.recordForCommunity("SUBSCRIPTION_CANCELLED", subscription.getCommunityId(), "PlatformSubscription", id, before, after);
        em.flush();
        return get(id);
    }

    @Transactional(readOnly = true)
    public SubscriptionView get(UUID id) {
        MapSqlParameterSource params = windowParams(properties.expiringWindowDays()).addValue("id", id);
        List<SubscriptionView> rows = jdbc.query(baseSelect() + " WHERE s.id = :id", params, rowMapper());
        if (rows.isEmpty()) {
            throw new NotFoundException();
        }
        return rows.get(0);
    }

    /**
     * @param status ACTIVE, EXPIRING, EXPIRED or CANCELLED (derived), or null for all
     * @param windowDays how soon counts as EXPIRING
     */
    @Transactional(readOnly = true)
    public PageResponse<SubscriptionView> list(String status, UUID communityId, int windowDays, PageRequest page) {
        MapSqlParameterSource params = windowParams(windowDays);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (communityId != null) {
            where.append(" AND t.community_id = :communityId");
            params.addValue("communityId", communityId);
        }
        if (status != null) {
            where.append(" AND t.display_status = :status");
            params.addValue("status", status);
        }
        String inner = baseSelect();
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + inner + ") t" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<SubscriptionView> items = jdbc.query("SELECT t.* FROM (" + inner + ") t" + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset", params, rowMapper());
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    private MapSqlParameterSource windowParams(int windowDays) {
        LocalDate today = today();
        return new MapSqlParameterSource().addValue("today", today).addValue("windowEnd", today.plusDays(windowDays));
    }

    private static String baseSelect() {
        return "SELECT s.id, s.community_id, c.name AS community_name, c.slug AS community_slug, p.code AS plan_code, p.name AS plan_name,"
                + " s.period_start, s.period_end, s.amount, s.paid_on, s.reference, s.cancel_reason, s.created_at, "
                + SubscriptionStatusRules.DISPLAY_SQL + " AS display_status"
                + " FROM platform_subscriptions s JOIN communities c ON c.id = s.community_id JOIN plans p ON p.id = s.plan_id";
    }

    private static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = order.getProperty().equals("id") ? "t.id" : SORT_SQL.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Sort property is not whitelisted: " + order.getProperty());
            }
            parts.add(column + (order.isDescending() ? " DESC" : " ASC"));
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    private org.springframework.jdbc.core.RowMapper<SubscriptionView> rowMapper() {
        LocalDate today = today();
        return (rs, row) -> {
            LocalDate end = rs.getDate("period_end").toLocalDate();
            return new SubscriptionView(
                    rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class), rs.getString("community_name"),
                    rs.getString("community_slug"), rs.getString("plan_code"), rs.getString("plan_name"),
                    rs.getDate("period_start").toLocalDate(), end, Money.of(rs.getBigDecimal("amount")),
                    rs.getDate("paid_on").toLocalDate(), rs.getString("reference"), rs.getString("display_status"),
                    rs.getString("cancel_reason"), ChronoUnit.DAYS.between(today, end), rs.getTimestamp("created_at").toInstant());
        };
    }

    private PlatformSubscription findEntity(UUID id) {
        UUID communityId = jdbc.queryForList("SELECT community_id FROM platform_subscriptions WHERE id = :id", new MapSqlParameterSource("id", id), UUID.class)
                .stream().findFirst().orElseThrow(NotFoundException::new);
        return subscriptions.findByIdAndCommunityId(id, communityId).orElseThrow(NotFoundException::new);
    }

    private static Map<String, Object> snapshot(PlatformSubscription s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("plan", s.getPlan().getCode());
        map.put("periodStart", s.getPeriodStart().toString());
        map.put("periodEnd", s.getPeriodEnd().toString());
        map.put("amount", s.getAmount().toPlainString());
        map.put("paidOn", s.getPaidOn().toString());
        map.put("reference", s.getReference());
        map.put("status", s.getStatus().name());
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}

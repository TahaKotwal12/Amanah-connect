package com.amanahconnect.mail;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Masking;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.mail.AdminEmailDtos.OutboxItem;
import com.amanahconnect.mail.AdminEmailDtos.OutboxStats;
import com.amanahconnect.mail.AdminEmailDtos.SuppressionView;
import com.amanahconnect.mail.AdminEmailDtos.TemplateView;
import com.amanahconnect.notification.EmailSuppressionRepository;
import com.amanahconnect.notification.SuppressionReason;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The super admin's view of the email engine: templates and previews (dev and staging), the outbox, failures, and the suppression list. */
@Service
@Transactional
public class AdminEmailService {

    private final MailTemplates templates;
    private final MailRenderer renderer;
    private final EmailProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final EmailSuppressionRepository suppressionRepository;
    private final SuppressionService suppressions;
    private final AuditService audit;
    private final Clock clock;

    public AdminEmailService(MailTemplates templates, MailRenderer renderer, EmailProperties properties, NamedParameterJdbcTemplate jdbc,
                             EmailSuppressionRepository suppressionRepository, SuppressionService suppressions, AuditService audit, Clock clock) {
        this.templates = templates;
        this.renderer = renderer;
        this.properties = properties;
        this.jdbc = jdbc;
        this.suppressionRepository = suppressionRepository;
        this.suppressions = suppressions;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- template preview (never in production) ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        requirePreview();
        return templates.all().stream()
                .map(t -> new TemplateView(t.name(), t.audience(), t.sensitive(), t.required(), t.subjectFor(MailSamples.payload(t.name()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public RenderedMail preview(String name) {
        requirePreview();
        MailTemplate template = templates.find(name).orElseThrow(NotFoundException::new);
        return renderer.render(name, MailSamples.payload(name), MailSamples.branding(template.audience()), name.equals("member-receipt"));
    }

    /** Off in production: the preview endpoints answer 404 as if they did not exist. */
    private void requirePreview() {
        if (!properties.previewEnabled()) throw new NotFoundException();
    }

    // ---- outbox -----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OutboxItem> outbox(String status, String template, UUID communityId, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (status != null) { where.append(" AND status = :status"); params.addValue("status", status); }
        if (template != null && !template.isBlank()) { where.append(" AND template = :template"); params.addValue("template", template); }
        if (communityId != null) { where.append(" AND community_id = :community"); params.addValue("community", communityId); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM email_outbox" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<OutboxItem> items = jdbc.query(
                "SELECT id, community_id, to_email::text AS to_email, template, status, attempts, next_attempt_at, last_attempt_at, sent_at, error, created_at FROM email_outbox"
                        + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
                params, (rs, n) -> new OutboxItem(rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class), Masking.email(rs.getString("to_email")), rs.getString("template"),
                        rs.getString("status"), rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("last_attempt_at")),
                        instant(rs.getTimestamp("sent_at")), rs.getString("error"), instant(rs.getTimestamp("created_at"))));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    @Transactional(readOnly = true)
    public OutboxStats stats() {
        MapSqlParameterSource params = new MapSqlParameterSource("since", Timestamp.from(clock.instant().minusSeconds(86_400)));
        long[] n = new long[3];
        Timestamp[] oldest = new Timestamp[1];
        jdbc.query("SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending, count(*) FILTER (WHERE status = 'FAILED') AS failed,"
                        + " count(*) FILTER (WHERE status = 'SENT' AND sent_at >= :since) AS sent, min(created_at) FILTER (WHERE status = 'PENDING') AS oldest FROM email_outbox",
                params, rs -> { n[0] = rs.getLong("pending"); n[1] = rs.getLong("failed"); n[2] = rs.getLong("sent"); oldest[0] = rs.getTimestamp("oldest"); });
        return new OutboxStats(n[0], n[1], n[2], instant(oldest[0]), suppressionRepository.count());
    }

    /** Puts a FAILED mail back in the queue with its attempts reset (after the cause was fixed). A mail that is not FAILED is a 404. */
    public OutboxItem retry(UUID id) {
        int changed = jdbc.update("UPDATE email_outbox SET status = 'PENDING', attempts = 0, error = NULL, next_attempt_at = now() WHERE id = :id AND status = 'FAILED'", new MapSqlParameterSource("id", id));
        if (changed == 0) throw new NotFoundException();
        audit.record("EMAIL_RETRY_REQUESTED", AuditService.currentActorId(), null, "EmailOutbox", id, Map.of("status", "FAILED"), Map.of("status", "PENDING"));
        return jdbc.query(
                "SELECT id, community_id, to_email::text AS to_email, template, status, attempts, next_attempt_at, last_attempt_at, sent_at, error, created_at FROM email_outbox WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> new OutboxItem(rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class), Masking.email(rs.getString("to_email")),
                        rs.getString("template"), rs.getString("status"), rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("last_attempt_at")),
                        instant(rs.getTimestamp("sent_at")), rs.getString("error"), instant(rs.getTimestamp("created_at")))).stream().findFirst().orElseThrow(NotFoundException::new);
    }

    // ---- suppression list ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<SuppressionView> suppressions(Pageable page) {
        return PageResponse.from(suppressionRepository.findAll(page), s -> new SuppressionView(s.getId(), Masking.email(s.getEmail()), s.getReason().name(), s.getSource(), s.getCreatedAt()));
    }

    public Map<String, Object> addSuppression(String email) {
        boolean added = suppressions.suppress(email, SuppressionReason.MANUAL, "ADMIN", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        return out;
    }

    public void removeSuppression(String email) {
        if (!suppressions.remove(email)) throw new NotFoundException();
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

package com.amanahconnect.support.tenant;

import com.amanahconnect.audit.Audited;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.support.TestData;
import com.amanahconnect.support.tenant.TestSupportDtos.MemberView;
import com.amanahconnect.tenant.TenantGuard;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A model of how a real module's service behaves: every lookup is community-scoped and a miss is the same
 * 404; mutating methods are audited. {@link #unscopedGet} is the deliberate exception that proves the
 * Hibernate filter.
 */
@Service
@Transactional
public class TestSupportService {

    private final MemberRepository members;
    private final TenantGuard guard;
    private final PlanLimitService planLimits;
    private final EntityManager em;

    public TestSupportService(MemberRepository members, TenantGuard guard, PlanLimitService planLimits, EntityManager em) {
        this.members = members;
        this.guard = guard;
        this.planLimits = planLimits;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public MemberView get(UUID communityId, UUID id) {
        return view(guard.found(members.findByIdAndCommunityId(id, communityId)));
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberView> list(UUID communityId, PageRequest page) {
        return PageResponse.from(members.findByCommunityId(communityId, page), TestSupportService::view);
    }

    @Audited(action = "TEST_MEMBER_CREATED", entityType = "Member")
    public MemberView create(UUID communityId, String fullName) {
        planLimits.checkMemberLimit(communityId);
        Member member = new Member();
        member.setCommunityId(communityId);
        member.setMemberNo("T-" + TestData.unique());
        member.setFullName(fullName);
        return view(members.save(member));
    }

    @Audited(action = "TEST_MEMBER_RENAMED", entityType = "Member")
    public MemberView rename(UUID communityId, UUID id, String fullName) {
        Member member = guard.found(members.findByIdAndCommunityId(id, communityId));
        member.setFullName(fullName);
        return view(member);
    }

    @Audited(action = "TEST_MEMBER_DELETED", entityType = "Member")
    public MemberView softDelete(UUID communityId, UUID id) {
        Member member = guard.found(members.findByIdAndCommunityId(id, communityId));
        member.setDeletedAt(Instant.now());
        return view(member);
    }

    /** Saves, then fails: the change and the audit row must both disappear. */
    @Audited(action = "TEST_FAILS_AFTER_SAVE", entityType = "Member")
    public MemberView renameThenFail(UUID communityId, UUID id, String fullName) {
        rename(communityId, id, fullName);
        throw new IllegalStateException("boom after save");
    }

    /** The audit insert itself fails (entity type is longer than the column), so the rename must roll back. */
    @Audited(action = "TEST_AUDIT_FAILS", entityType = "TooLong-0123456789-0123456789-0123456789-0123456789-0123456789-0123456789-0123456789-0123456789-0123456789")
    public MemberView renameWithFailingAudit(UUID communityId, UUID id, String fullName) {
        Member member = guard.found(members.findByIdAndCommunityId(id, communityId));
        member.setFullName(fullName);
        return view(member);
    }

    /**
     * What a developer must never write: a lookup by id with NO community predicate. The Hibernate tenant
     * filter is the safety net that stops it leaking another community's row.
     */
    @Transactional(readOnly = true)
    public MemberView unscopedGet(UUID id) {
        return em.createQuery("select m from Member m where m.id = :id", Member.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(TestSupportService::view)
                .orElseThrow(com.amanahconnect.common.error.NotFoundException::new);
    }

    private static MemberView view(Member member) {
        return new MemberView(member.getId(), member.getMemberNo(), member.getFullName());
    }
}

package com.amanahconnect.member;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.member.InviteDtos.ApproveRegistrationRequest;
import com.amanahconnect.member.InviteDtos.RegistrationView;
import com.amanahconnect.member.InviteDtos.RejectRegistrationRequest;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin reviewing people who registered through an invite link. */
@Service
@Transactional
public class RegistrationService {

    private static final UUID NO_MEMBER = new UUID(0, 0);

    private final MemberRegistrationRepository registrations;
    private final MemberRepository members;
    private final MemberService memberService;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public RegistrationService(
            MemberRegistrationRepository registrations,
            MemberRepository members,
            MemberService memberService,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock) {
        this.registrations = registrations;
        this.members = members;
        this.memberService = memberService;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<RegistrationView> list(UUID communityId, RegistrationStatus status, Pageable pageable) {
        Page<MemberRegistration> page = status == null ? registrations.findByCommunityId(communityId, pageable) : registrations.findByCommunityIdAndStatus(communityId, status, pageable);
        return PageResponse.from(page, r -> view(communityId, r));
    }

    @Transactional(readOnly = true)
    public RegistrationView get(UUID communityId, UUID id) {
        return view(communityId, tenantGuard.found(registrations.findByIdAndCommunityId(id, communityId)));
    }

    /**
     * Creates the member (plan limit, duplicate email, numbering, welcome email) and marks the registration approved,
     * all or nothing: if any step fails the registration stays pending.
     */
    public RegistrationView approve(UUID communityId, UUID id, ApproveRegistrationRequest request) {
        MemberRegistration registration = pending(communityId, id);
        boolean allowDuplicate = request != null && Boolean.TRUE.equals(request.allowDuplicateEmail());
        String group = request != null && request.group() != null && !request.group().isBlank() ? request.group().trim() : registration.getGroupLabel();
        Member member = memberService.add(communityId,
                new MemberService.NewMember(registration.getFullName(), registration.getEmail(), registration.getPhone(), group, null,
                        registration.getCustomFields(), registration.isConsentEmail(), "REGISTRATION"),
                allowDuplicate);
        registration.setStatus(RegistrationStatus.APPROVED);
        registration.setReviewedBy(AuditService.currentActorId());
        registration.setReviewedAt(clock.instant());
        registration.setMember(member);
        registrations.save(registration);
        audit.record("MEMBER_REGISTRATION_APPROVED", "MemberRegistration", id, Map.of("status", "PENDING"), Map.of("status", "APPROVED", "memberId", member.getId().toString()));
        return view(communityId, registration);
    }

    public RegistrationView reject(UUID communityId, UUID id, RejectRegistrationRequest request) {
        MemberRegistration registration = pending(communityId, id);
        registration.setStatus(RegistrationStatus.REJECTED);
        registration.setReviewedBy(AuditService.currentActorId());
        registration.setReviewedAt(clock.instant());
        registration.setRejectionReason(request.reason().trim());
        registrations.save(registration);
        audit.record("MEMBER_REGISTRATION_REJECTED", "MemberRegistration", id, Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", request.reason().trim()));
        return view(communityId, registration);
    }

    private MemberRegistration pending(UUID communityId, UUID id) {
        MemberRegistration registration = tenantGuard.found(registrations.findWithLockByIdAndCommunityId(id, communityId));
        if (registration.getStatus() != RegistrationStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This registration was already " + registration.getStatus().name().toLowerCase() + ".");
        }
        return registration;
    }

    private RegistrationView view(UUID communityId, MemberRegistration r) {
        String usedBy = null;
        if (r.getStatus() == RegistrationStatus.PENDING && r.getEmail() != null) {
            usedBy = members.findFirstByCommunityIdAndEmailAndDeletedAtIsNullAndIdNot(communityId, r.getEmail(), NO_MEMBER).map(Member::getMemberNo).orElse(null);
        }
        Member member = r.getMember();
        return new RegistrationView(r.getId(), r.getFullName(), r.getEmail(), r.getPhone(), r.getGroupLabel(), r.isConsentEmail(), r.getStatus(),
                r.getCreatedAt(), r.getReviewedAt(), r.getRejectionReason(), member == null ? null : member.getId(), member == null ? null : member.getMemberNo(), usedBy);
    }
}

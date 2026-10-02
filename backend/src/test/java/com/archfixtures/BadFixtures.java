package com.archfixtures;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.audit.Audited;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately wrong code, kept OUTSIDE the com.amanahconnect package so Spring never scans it. It exists
 * only to prove, in ArchitectureRulesSelfTest, that every architecture rule fails on what it forbids and
 * passes on the correct version.
 */
public final class BadFixtures {

    private BadFixtures() {}

    public record BodyWithCommunityId(UUID communityId, String name) {}

    public record GoodBody(String name) {}

    public record View(UUID id, String name) {}

    public record MoneyHolder(double amount, float rate) {}

    @RestController
    @RequestMapping("/api/v1/community/bad")
    public static class BadController {
        MemberRepository repository;
        EntityManager entityManager;

        @GetMapping("/leak")
        public Member leaksAnEntity() {
            return null;
        }

        @GetMapping("/leak-list")
        public List<Member> leaksEntitiesInsideGenerics() {
            return List.of();
        }

        @PostMapping("/accepts-entity")
        public void acceptsAnEntity(@RequestBody Member member) {}

        @PostMapping("/mutates")
        public void mutatesWithoutAudit(@RequestBody BodyWithCommunityId body) {}

        @DeleteMapping("/{communityId}/things")
        public void deletesWithCommunityIdInPath(@PathVariable UUID communityId) {}

        @GetMapping("/query")
        public View queryParam(@RequestParam("communityId") UUID communityId) {
            return null;
        }
    }

    @RestController
    @RequestMapping("/api/v1/community/good")
    public static class GoodController {

        @GetMapping("/{id}")
        public View read(@PathVariable UUID id) {
            return null;
        }

        @Audited(action = "GOOD_CREATED")
        @PostMapping("/audited")
        public View audited(@RequestBody GoodBody body) {
            return null;
        }

        @AuditHandledBy("GoodService records it")
        @PostMapping("/handled")
        public void handled(@RequestBody GoodBody body) {}
    }

    public interface BadTenantRepository extends TenantRepository<Member, UUID> {
        Optional<Member> findById(UUID id);

        List<Member> findByFullName(String fullName);

        void deleteByMemberNo(String memberNo);

        long countByStatus(String status);
    }

    public interface GoodTenantRepository extends TenantRepository<Member, UUID> {
        List<Member> findByCommunityIdAndFullName(UUID communityId, String fullName);

        @CrossTenantLookup("resolves the community from a token")
        Optional<Member> findByMemberNo(String memberNo);
    }
}

package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.TestData;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Entity behaviour that is easy to get subtly wrong: JSON, email case, versions, laziness, equality. */
@Transactional
class EntityMappingIT extends AbstractIntegrationTest {

    @Autowired TestData data;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired CommunityRepository communities;
    @Autowired MemberRepository members;
    @Autowired InvoiceRepository invoices;

    // ---- citext -----------------------------------------------------------------------------

    @Test
    void emailLookupIsCaseInsensitive() {
        String unique = TestData.unique();
        User user = new User();
        user.setEmail("Mixed.Case-" + unique + "@Example.Test");
        user.setFullName("Case Test");
        user.setRole(UserRole.COMMUNITY_ADMIN);
        users.save(user);

        Optional<User> found = users.findByEmail("mixed.case-" + unique + "@EXAMPLE.TEST");

        assertThat(found).as("citext parameter binding keeps the comparison case-insensitive").isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
    }

    @Test
    void emailUniquenessIgnoresCase() {
        String unique = TestData.unique();
        User first = new User();
        first.setEmail("dup-" + unique + "@example.test");
        first.setFullName("One");
        first.setRole(UserRole.COMMUNITY_ADMIN);
        users.save(first);

        User second = new User();
        second.setEmail("DUP-" + unique + "@Example.Test");
        second.setFullName("Two");
        second.setRole(UserRole.COMMUNITY_ADMIN);

        assertThatThrownBy(
                        () -> {
                            users.save(second);
                            em.flush();
                        })
                .hasMessageContaining("uq_users_email");
    }

    // ---- jsonb ------------------------------------------------------------------------------

    @Test
    void jsonbColumnsRoundTripNestedValuesAndNulls() {
        Community community = data.community();
        community.setSettings(Map.of("theme", "teal", "features", Map.of("events", true, "levels", List.of(1, 2, 3))));
        communities.save(community);
        em.flush();
        em.clear();

        Community reloaded = communities.findById(community.getId()).orElseThrow();

        assertThat(reloaded.getSettings()).containsEntry("theme", "teal");
        assertThat(reloaded.getSettings().get("features")).isEqualTo(Map.of("events", true, "levels", List.of(1, 2, 3)));

        Plan enterprise = data.plan("ENTERPRISE");
        assertThat(enterprise.getLimits()).containsKey("max_members");
        assertThat(enterprise.getLimits().get("max_members")).as("JSON null means unlimited").isNull();
        assertThat(enterprise.getLimits().get("emails_per_month")).isEqualTo(50000);
        assertThat(enterprise.getFeatures()).containsEntry("priority_support", true);
    }

    @Test
    void memberCustomFieldsRoundTrip() {
        Community community = data.community();
        Member member = data.member(community);
        member.setCustomFields(Map.of("flat", "B-12", "vehicles", 2));
        members.save(member);
        em.flush();
        em.clear();

        Member reloaded = members.findByIdAndCommunityId(member.getId(), community.getId()).orElseThrow();

        assertThat(reloaded.getCustomFields()).containsEntry("flat", "B-12").containsEntry("vehicles", 2);
    }

    // ---- money ------------------------------------------------------------------------------

    @Test
    void moneyKeepsTwoDecimalsAsBigDecimal() {
        Community community = data.community();
        Invoice invoice = data.issuedInvoice(community, data.member(community), "INV-M/1", "123456789012.34");
        em.clear();

        Invoice reloaded = invoices.findByIdAndCommunityId(invoice.getId(), community.getId()).orElseThrow();

        assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("123456789012.34"));
        assertThat(reloaded.getAmount().scale()).isEqualTo(2);
        assertThat(reloaded.getAmountPaid()).isEqualTo(new BigDecimal("0.00"));
    }

    // ---- timestamps and ids -----------------------------------------------------------------

    @Test
    void idsAndUtcTimestampsAreAssignedAndUpdatedAtAdvances() throws Exception {
        Community community = data.community();
        assertThat(community.getId()).isNotNull();
        Instant created = community.getCreatedAt();
        assertThat(created).isCloseTo(Instant.now(), within(Duration.ofSeconds(30)));
        assertThat(community.getUpdatedAt()).isNotNull();

        Thread.sleep(20);
        community.setName("Renamed " + TestData.unique());
        communities.save(community);
        em.flush();

        assertThat(community.getCreatedAt()).isEqualTo(created);
        assertThat(community.getUpdatedAt()).isAfter(created);
    }

    @Test
    void updatedAtAlsoAdvancesForWritesThatBypassJpa() throws Exception {
        Community community = data.community();
        Instant before = em.createQuery("select c.updatedAt from Community c where c.id = :id", Instant.class)
                .setParameter("id", community.getId()).getSingleResult();

        Thread.sleep(20);
        em.createNativeQuery("update communities set city = 'Pune' where id = :id")
                .setParameter("id", community.getId()).executeUpdate();
        em.clear();

        Instant after = em.createQuery("select c.updatedAt from Community c where c.id = :id", Instant.class)
                .setParameter("id", community.getId()).getSingleResult();
        assertThat(after).as("DB trigger keeps updated_at honest").isAfter(before);
    }

    // ---- laziness and equality --------------------------------------------------------------

    @Test
    void associationsAreLazy() {
        Community community = data.community();
        UUID invoiceId = data.issuedInvoice(community, data.member(community), "INV-L/1", "10.00").getId();
        em.clear();

        Invoice invoice = invoices.findByIdAndCommunityId(invoiceId, community.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(invoice.getMember())).as("member is a proxy until used").isFalse();
        assertThat(invoice.getMember().getFullName()).startsWith("Member");
        assertThat(Hibernate.isInitialized(invoice.getMember())).isTrue();
    }

    @Test
    void equalityIsByIdAndSurvivesProxies() {
        Community community = data.community();
        Member member = data.member(community);
        em.clear();
        Member loaded = members.findByIdAndCommunityId(member.getId(), community.getId()).orElseThrow();
        em.clear();
        Member proxy = em.getReference(Member.class, member.getId());

        assertThat(loaded).isEqualTo(proxy).hasSameHashCodeAs(proxy);
        assertThat(new Member()).as("unsaved entities are only equal to themselves").isNotEqualTo(new Member());
        Member unsaved = new Member();
        assertThat(unsaved).isEqualTo(unsaved);
        assertThat(loaded).isNotEqualTo(data.member(community));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return org.assertj.core.api.Assertions.within(duration.toSeconds(), java.time.temporal.ChronoUnit.SECONDS);
    }
}

package com.amanahconnect.support;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.billing.FeeKind;
import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.billing.InvoiceStatus;
import com.amanahconnect.billing.PaymentMethod;
import com.amanahconnect.billing.PaymentRecord;
import com.amanahconnect.billing.PaymentRecordRepository;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.ledger.LedgerCategory;
import com.amanahconnect.ledger.LedgerCategoryRepository;
import com.amanahconnect.ledger.LedgerType;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds valid rows for tests. Every call uses unique values, so tests never collide. */
@Component
@Transactional
public class TestData {

    private final PlanRepository plans;
    private final UserRepository users;
    private final CommunityRepository communities;
    private final MemberRepository members;
    private final InvoiceRepository invoices;
    private final PaymentRecordRepository payments;
    private final LedgerCategoryRepository categories;
    private final EntityManager em;

    public TestData(
            PlanRepository plans,
            UserRepository users,
            CommunityRepository communities,
            MemberRepository members,
            InvoiceRepository invoices,
            PaymentRecordRepository payments,
            LedgerCategoryRepository categories,
            EntityManager em) {
        this.plans = plans;
        this.users = users;
        this.communities = communities;
        this.members = members;
        this.invoices = invoices;
        this.payments = payments;
        this.categories = categories;
        this.em = em;
    }

    public static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public Plan plan(String code) {
        return plans.findByCode(code).orElseThrow();
    }

    /** A throwaway plan with the given limits and features (the seeded plans are left alone). */
    public Plan customPlan(String name, Map<String, Object> limits, Map<String, Object> features) {
        Plan plan = new Plan();
        plan.setCode("TEST_" + unique().toUpperCase());
        plan.setName(name);
        plan.setLimits(limits);
        plan.setFeatures(features);
        plan.setPublicPlan(false);
        plans.save(plan);
        em.flush();
        return plan;
    }

    public Community communityOn(Plan plan) {
        Community community = new Community();
        community.setName("Community " + unique());
        community.setSlug("c-" + unique());
        community.setPlan(plan);
        community.setStatus(CommunityStatus.ACTIVE);
        communities.save(community);
        em.flush();
        return community;
    }

    public User user(UserRole role) {
        User user = new User();
        user.setEmail("user-" + unique() + "@example.test");
        user.setFullName("Test " + role);
        user.setRole(role);
        users.save(user);
        em.flush();
        return user;
    }

    /** Inserting a community fires the DB trigger that copies default categories and settings. */
    public Community community() {
        Community community = new Community();
        community.setName("Community " + unique());
        community.setSlug("c-" + unique());
        community.setPlan(plan("STARTER"));
        community.setStatus(CommunityStatus.ACTIVE);
        communities.save(community);
        em.flush();
        return community;
    }

    public Member member(Community community) {
        Member member = new Member();
        member.setCommunityId(community.getId());
        member.setMemberNo("M-" + unique());
        member.setFullName("Member " + unique());
        members.save(member);
        em.flush();
        return member;
    }

    public Invoice issuedInvoice(Community community, Member member, String invoiceNo, String amount) {
        Invoice invoice = new Invoice();
        invoice.setCommunityId(community.getId());
        invoice.setMember(member);
        invoice.setInvoiceNo(invoiceNo);
        invoice.setKind(FeeKind.MEMBERSHIP);
        invoice.setAmount(new BigDecimal(amount));
        invoice.setDueDate(LocalDate.now().plusDays(10));
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoices.save(invoice);
        em.flush();
        return invoice;
    }

    public PaymentRecord payment(Community community, Member member, Invoice invoice, String amount, UUID recordedBy) {
        PaymentRecord payment = new PaymentRecord();
        payment.setCommunityId(community.getId());
        payment.setMember(member);
        payment.setInvoice(invoice);
        payment.setAmount(new BigDecimal(amount));
        payment.setMethod(PaymentMethod.CASH);
        payment.setReceivedOn(LocalDate.now());
        payment.setRecordedBy(recordedBy);
        payments.save(payment);
        em.flush();
        return payment;
    }

    public LedgerCategory category(Community community, LedgerType type, String name) {
        return categories.findByCommunityIdAndNameAndType(community.getId(), name, type).orElseThrow();
    }
}

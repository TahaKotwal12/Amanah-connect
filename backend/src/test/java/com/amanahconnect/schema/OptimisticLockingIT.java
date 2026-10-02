package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.Member;
import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Not @Transactional: each step must be its own committed transaction to create a lost-update race. */
class OptimisticLockingIT extends AbstractIntegrationTest {

    @Autowired TestData data;
    @Autowired CommunityRepository communities;
    @Autowired InvoiceRepository invoices;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void staleInvoiceUpdateIsRejected() {
        Community community = data.community();
        Member member = data.member(community);
        UUID id = data.issuedInvoice(community, member, "INV-V/1", "100.00").getId();
        Invoice staleCopy = tx().execute(s -> invoices.findByIdAndCommunityId(id, community.getId()).orElseThrow());

        tx().executeWithoutResult(s -> {
            Invoice fresh = invoices.findByIdAndCommunityId(id, community.getId()).orElseThrow();
            fresh.setPeriod("2026-04");
        });

        staleCopy.setPeriod("2026-05");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> invoices.save(staleCopy)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void staleCommunityUpdateIsRejected() {
        UUID id = data.community().getId();
        Community staleCopy = tx().execute(s -> communities.findById(id).orElseThrow());

        tx().executeWithoutResult(s -> communities.findById(id).orElseThrow().setCity("Pune"));

        staleCopy.setCity("Delhi");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> communities.save(staleCopy)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}

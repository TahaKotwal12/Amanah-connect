package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.community.Community;
import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Not @Transactional on purpose: the point is real, committed, concurrent transactions.
 */
class NumberingServiceIT extends AbstractIntegrationTest {

    private static final String FY = "2026-27";

    @Autowired NumberingService numbering;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Test
    void twentyConcurrentThreadsGetNoGapsAndNoDuplicates() throws Exception {
        int threads = 20;
        int perThread = 5;
        Community community = data.community(); // no counter row exists yet: the first-use race is part of the test
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    List<String> mine = new ArrayList<>();
                                    ready.countDown();
                                    go.await();
                                    for (int i = 0; i < perThread; i++) {
                                        mine.add(
                                                newTransaction()
                                                        .execute(
                                                                status ->
                                                                        numbering.next(
                                                                                community.getId(),
                                                                                CounterType.RECEIPT,
                                                                                FY)));
                                    }
                                    return mine;
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown(); // release all 20 threads at once
            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }

            int total = threads * perThread;
            assertThat(all).as("no duplicates").doesNotHaveDuplicates().hasSize(total);
            List<Long> values = all.stream().map(NumberingServiceIT::suffix).sorted().toList();
            assertThat(values)
                    .as("no gaps: exactly 1..N")
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList());
            assertThat(lastValue(community, CounterType.RECEIPT)).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rolledBackTransactionGivesItsNumberBack() {
        Community community = data.community();

        String first = newTransaction().execute(status -> numbering.next(community.getId(), CounterType.INVOICE, FY));
        assertThat(first).isEqualTo("INV-2026-27/000001");

        assertThatThrownBy(
                        () ->
                                newTransaction()
                                        .executeWithoutResult(
                                                status -> {
                                                    numbering.next(community.getId(), CounterType.INVOICE, FY);
                                                    throw new IllegalStateException("document insert failed");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        String second = newTransaction().execute(status -> numbering.next(community.getId(), CounterType.INVOICE, FY));
        assertThat(second).as("the burned number is reissued, so there is no gap").isEqualTo("INV-2026-27/000002");
        assertThat(lastValue(community, CounterType.INVOICE)).isEqualTo(2);
    }

    @Test
    void sequencesAreIndependentPerTypeYearAndCommunity() {
        Community a = data.community();
        Community b = data.community();

        List<String> numbers =
                newTransaction()
                        .execute(
                                status ->
                                        List.of(
                                                numbering.next(a.getId(), CounterType.INVOICE, "2026-27"),
                                                numbering.next(a.getId(), CounterType.INVOICE, "2026-27"),
                                                numbering.next(a.getId(), CounterType.RECEIPT, "2026-27"),
                                                numbering.next(a.getId(), CounterType.INVOICE, "2027-28"),
                                                numbering.next(b.getId(), CounterType.INVOICE, "2026-27")));

        assertThat(numbers)
                .containsExactly(
                        "INV-2026-27/000001",
                        "INV-2026-27/000002",
                        "RCP-2026-27/000001",
                        "INV-2027-28/000001",
                        "INV-2026-27/000001");
    }

    @Test
    void mustRunInsideTheCallersTransaction() {
        Community community = data.community();

        assertThatThrownBy(() -> numbering.next(community.getId(), CounterType.INVOICE, FY))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void numbersAreZeroPaddedAndKeepGrowingPastSixDigits() {
        assertThat(NumberingService.format(CounterType.RECEIPT, "2026-27", 123)).isEqualTo("RCP-2026-27/000123");
        assertThat(NumberingService.format(CounterType.INVOICE, "2026", 1_000_000)).isEqualTo("INV-2026/1000000");
    }

    private long lastValue(Community community, CounterType type) {
        return jdbc.queryForObject(
                "select last_value from document_counters where community_id = ? and counter_type = ? and financial_year = ?",
                Long.class,
                community.getId(),
                type.name(),
                FY);
    }

    private static long suffix(String number) {
        return Long.parseLong(number.substring(number.indexOf('/') + 1));
    }

}

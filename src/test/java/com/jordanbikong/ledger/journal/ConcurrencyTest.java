package com.jordanbikong.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jordanbikong.ledger.TestcontainersConfiguration;
import com.jordanbikong.ledger.account.AccountService;
import com.jordanbikong.ledger.account.AccountType;
import com.jordanbikong.ledger.account.dto.CreateAccountRequest;
import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.PostingRequest;

/**
 * Many transactions on the same accounts at once. These go through the
 * controller bean (idempotency, posting, locking) without HTTP, so 50 threads
 * really do run in parallel against Postgres.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConcurrencyTest {

    private static final int THREADS = 50;

    @Autowired
    private JournalEntryController controller;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        long cash = createAccount(AccountType.ASSET);
        long equity = createAccount(AccountType.EQUITY);
        long rent = createAccount(AccountType.EXPENSE);
        post(transfer("Owner invests", cash, equity, 1_000));

        // 50 withdrawals of 100 against a balance of 1,000: only 10 can fit.
        List<String> outcomes = runAtOnce(i -> transfer("Withdrawal " + i, rent, cash, 100));

        assertThat(outcomes).filteredOn("posted"::equals).hasSize(10);
        assertThat(outcomes).filteredOn("rejected"::equals).hasSize(THREADS - 10);
        assertThat(balanceOf(cash)).isZero();
        assertThat(balanceOf(rent)).isEqualTo(1_000);
        assertThat(balanceOf(cash)).isEqualTo(sumOfPostings(cash));
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        long a = createAccount(AccountType.ASSET);
        long b = createAccount(AccountType.ASSET);
        long equity = createAccount(AccountType.EQUITY);
        post(transfer("Fund A", a, equity, 10_000));
        post(transfer("Fund B", b, equity, 10_000));

        // Half move A → B, half move B → A, all at the same moment. Without a
        // fixed lock order, T1 locks A and waits for B while T2 locks B and
        // waits for A: a deadlock.
        List<String> outcomes = runAtOnce(i -> i % 2 == 0
                ? transfer("A to B " + i, b, a, 100)
                : transfer("B to A " + i, a, b, 100));

        assertThat(outcomes).containsOnly("posted");
        assertThat(balanceOf(a)).isEqualTo(10_000);
        assertThat(balanceOf(b)).isEqualTo(10_000);
        assertThat(balanceOf(a)).isEqualTo(sumOfPostings(a));
        assertThat(balanceOf(b)).isEqualTo(sumOfPostings(b));
    }

    // --- helpers -----------------------------------------------------------

    private interface RequestForThread {
        CreateJournalEntryRequest build(int thread);
    }

    /**
     * Starts every request at the same instant and reports each outcome:
     * "posted", "rejected" (422), or the exception's class name for anything
     * else, such as a deadlock, so an unexpected failure shows up in the assert.
     */
    private List<String> runAtOnce(RequestForThread requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                CreateJournalEntryRequest request = requests.build(i);
                Callable<String> attempt = () -> {
                    start.await();
                    try {
                        controller.create(UUID.randomUUID().toString(), request);
                        return "posted";
                    } catch (LedgerRuleException e) {
                        return "rejected";
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
                    }
                };
                futures.add(pool.submit(attempt));
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Debit one account, credit the other. */
    private static CreateJournalEntryRequest transfer(String description, long debit, long credit, long amount) {
        return new CreateJournalEntryRequest(description, List.of(
                new PostingRequest(debit, Direction.DEBIT, amount),
                new PostingRequest(credit, Direction.CREDIT, amount)));
    }

    private void post(CreateJournalEntryRequest request) {
        controller.create(UUID.randomUUID().toString(), request);
    }

    private long createAccount(AccountType type) {
        return accounts.create(new CreateAccountRequest(type + "-" + UUID.randomUUID(), type, "CAD", false)).getId();
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    /** The balance recomputed from scratch, on the account's normal side. */
    private long sumOfPostings(long accountId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN (p.direction = 'DEBIT') = (a.type IN ('ASSET', 'EXPENSE'))
                                         THEN p.amount ELSE -p.amount END), 0)
                  FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                 WHERE a.id = ?
                """, Long.class, accountId);
    }
}

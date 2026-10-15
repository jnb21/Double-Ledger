package com.jordanbikong.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the database enforces the ledger rules on its own, using plain SQL
 * with no application code in between. Each test commits for real, because
 * the balance check is deferred until COMMIT.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String RESTRICT_VIOLATION = "23001";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void balancedEntryCommits() {
        long cash = createAccount("ASSET", "CAD");
        long revenue = createAccount("REVENUE", "CAD");

        long entryId = tx.execute(status -> {
            long id = createEntry("Sale");
            post(id, cash, "DEBIT", 100);
            post(id, revenue, "CREDIT", 100);
            return id;
        });

        Integer postings = jdbc.queryForObject(
                "SELECT count(*) FROM postings WHERE entry_id = ?", Integer.class, entryId);
        assertThat(postings).isEqualTo(2);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        long cash = createAccount("ASSET", "CAD");
        long revenue = createAccount("REVENUE", "CAD");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long id = createEntry("Bad sale");
            post(id, cash, "DEBIT", 100);
            post(id, revenue, "CREDIT", 90);
        }))
                .satisfies(e -> assertSqlState(e, CHECK_VIOLATION))
                .rootCause().hasMessageContaining("unbalanced");
    }

    @Test
    void entryWithOnePostingIsRejected() {
        long cash = createAccount("ASSET", "CAD");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long id = createEntry("Lonely posting");
            post(id, cash, "DEBIT", 100);
        }))
                .satisfies(e -> assertSqlState(e, CHECK_VIOLATION))
                .rootCause().hasMessageContaining("at least 2");
    }

    @Test
    void entryMixingCurrenciesIsRejected() {
        long cad = createAccount("ASSET", "CAD");
        long usd = createAccount("ASSET", "USD");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long id = createEntry("CAD to USD at 1:1");
            post(id, cad, "CREDIT", 100);
            post(id, usd, "DEBIT", 100);
        }))
                .satisfies(e -> assertSqlState(e, CHECK_VIOLATION))
                .rootCause().hasMessageContaining("currencies");
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        long cash = createAccount("ASSET", "CAD");
        long revenue = createAccount("REVENUE", "CAD");

        for (long amount : new long[] {0, -100}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                long id = createEntry("Bad amount");
                post(id, cash, "DEBIT", amount);
                post(id, revenue, "CREDIT", amount);
            })).satisfies(e -> assertSqlState(e, CHECK_VIOLATION));
        }
    }

    @Test
    void postingsCannotBeUpdatedOrDeleted() {
        long cash = createAccount("ASSET", "CAD");
        long revenue = createAccount("REVENUE", "CAD");
        long entryId = tx.execute(status -> {
            long id = createEntry("Sale");
            post(id, cash, "DEBIT", 100);
            post(id, revenue, "CREDIT", 100);
            return id;
        });

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE postings SET amount = 1 WHERE entry_id = ?", entryId))
                .satisfies(e -> assertSqlState(e, RESTRICT_VIOLATION));
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM postings WHERE entry_id = ?", entryId))
                .satisfies(e -> assertSqlState(e, RESTRICT_VIOLATION));
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM journal_entries WHERE id = ?", entryId))
                .satisfies(e -> assertSqlState(e, RESTRICT_VIOLATION));
    }

    @Test
    void entryCanBeReversedOnlyOnce() {
        long cash = createAccount("ASSET", "CAD");
        long revenue = createAccount("REVENUE", "CAD");
        long original = tx.execute(status -> {
            long id = createEntry("Sale");
            post(id, cash, "DEBIT", 100);
            post(id, revenue, "CREDIT", 100);
            return id;
        });

        tx.executeWithoutResult(status -> reverse(original, cash, revenue));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reverse(original, cash, revenue)))
                .satisfies(e -> assertSqlState(e, "23505")); // unique_violation
    }

    // --- helpers -----------------------------------------------------------

    private long createAccount(String type, String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, type, currency) VALUES (?, ?, ?) RETURNING id",
                Long.class, type + "-" + UUID.randomUUID(), type, currency);
    }

    private long createEntry(String description) {
        return jdbc.queryForObject(
                "INSERT INTO journal_entries (description) VALUES (?) RETURNING id",
                Long.class, description);
    }

    private void post(long entryId, long accountId, String direction, long amount) {
        jdbc.update(
                "INSERT INTO postings (entry_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                entryId, accountId, direction, amount);
    }

    private void reverse(long originalId, long debitedAccount, long creditedAccount) {
        long id = jdbc.queryForObject(
                "INSERT INTO journal_entries (description, reverses_entry_id) VALUES (?, ?) RETURNING id",
                Long.class, "Reversal of " + originalId, originalId);
        post(id, debitedAccount, "CREDIT", 100);
        post(id, creditedAccount, "DEBIT", 100);
    }

    /** Walks the exception's cause chain to the SQLException and checks its SQLSTATE code. */
    private static void assertSqlState(Throwable thrown, String expected) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isEqualTo(expected);
                return;
            }
        }
        throw new AssertionError("No SQLException in cause chain of " + thrown);
    }
}

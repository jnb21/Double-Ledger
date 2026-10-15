package com.jordanbikong.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jordanbikong.ledger.TestcontainersConfiguration;

/** POST /journal-entries end to end: HTTP → PostingService → Postgres (with the V1 trigger). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JournalEntryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostingService postingService;

    @Test
    void balancedEntryReturns201AndUpdatesBothBalances() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");

        postEntry("Sale", line(cash, "DEBIT", 10_000), line(sales, "CREDIT", 10_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/journal-entries/\\d+")))
                .andExpect(jsonPath("$.description").value("Sale"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andExpect(jsonPath("$.postings[0].accountId").value(cash))
                .andExpect(jsonPath("$.postings[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.postings[0].amount").value(10_000));

        // Both balances go up: a debit raises an ASSET, a credit raises REVENUE.
        assertThat(balanceOf(cash)).isEqualTo(10_000);
        assertThat(balanceOf(sales)).isEqualTo(10_000);
    }

    @Test
    void balancesMoveOnEachAccountsNormalSide() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long equity = createAccount("EQUITY", "CAD");
        long rent = createAccount("EXPENSE", "CAD");

        postEntry("Owner invests", line(cash, "DEBIT", 50_000), line(equity, "CREDIT", 50_000))
                .andExpect(status().isCreated());
        postEntry("Pay rent", line(rent, "DEBIT", 20_000), line(cash, "CREDIT", 20_000))
                .andExpect(status().isCreated());

        assertThat(balanceOf(cash)).isEqualTo(30_000);    // +50,000 then -20,000
        assertThat(balanceOf(equity)).isEqualTo(50_000);
        assertThat(balanceOf(rent)).isEqualTo(20_000);
    }

    @Test
    void cachedBalanceEqualsSumOfPostings() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long fees = createAccount("EXPENSE", "CAD");

        postEntry("Sale 1", line(cash, "DEBIT", 1_250), line(sales, "CREDIT", 1_250));
        postEntry("Sale 2", line(cash, "DEBIT", 3_000), line(sales, "CREDIT", 3_000));
        postEntry("Card fee", line(fees, "DEBIT", 99), line(cash, "CREDIT", 99));
        // One entry, three lines: a split sale.
        postEntry("Split sale", line(cash, "DEBIT", 700), line(fees, "DEBIT", 300), line(sales, "CREDIT", 1_000));

        for (long account : new long[] {cash, sales, fees}) {
            assertThat(balanceOf(account))
                    .as("cached balance of account %d", account)
                    .isEqualTo(sumOfPostings(account));
        }
    }

    @Test
    void unbalancedEntryReturns422AndChangesNothing() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");

        postEntry("Bad sale", line(cash, "DEBIT", 100), line(sales, "CREDIT", 90))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Ledger rule violated"))
                .andExpect(jsonPath("$.detail").value("Entry is unbalanced: debits 100 != credits 90"));

        assertThat(balanceOf(cash)).isZero();
        assertThat(postingCount(cash)).isZero();
    }

    @Test
    void mixedCurrenciesReturn422() throws Exception {
        long cad = createAccount("ASSET", "CAD");
        long usd = createAccount("ASSET", "USD");

        postEntry("CAD to USD", line(usd, "DEBIT", 100), line(cad, "CREDIT", 100))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Entry mixes currencies: CAD, USD"));
    }

    @Test
    void amountsThatOverflowReturn422() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");

        postEntry("Overflow", line(cash, "DEBIT", Long.MAX_VALUE), line(cash, "DEBIT", 1),
                line(sales, "CREDIT", Long.MAX_VALUE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Posting amounts are too large"));
    }

    @Test
    void unknownAccountReturns404AndChangesNothing() throws Exception {
        long cash = createAccount("ASSET", "CAD");

        postEntry("Ghost", line(cash, "DEBIT", 100), line(Long.MAX_VALUE, "CREDIT", 100))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account " + Long.MAX_VALUE + " does not exist"));

        assertThat(balanceOf(cash)).isZero();
    }

    @Test
    void malformedLinesReturn400() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");

        // One line only.
        postEntry("Lonely", line(cash, "DEBIT", 100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("postings"));
        // Zero amount.
        postEntry("Zero", line(cash, "DEBIT", 0), line(sales, "CREDIT", 0))
                .andExpect(status().isBadRequest());
        // Unknown direction.
        postEntry("Sideways", line(cash, "SIDEWAYS", 100), line(sales, "CREDIT", 100))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsTheEntryWithItsPostings() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long id = idOf(postEntry("Sale", line(cash, "DEBIT", 500), line(sales, "CREDIT", 500)));

        mvc.perform(get("/journal-entries/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andExpect(jsonPath("$.postings[1].accountId").value(sales));
    }

    @Test
    void getMissingEntryReturns404() throws Exception {
        mvc.perform(get("/journal-entries/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Journal entry " + Long.MAX_VALUE + " does not exist"));
    }

    @Test
    void accountHistoryListsPostingsNewestFirst() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        postEntry("First sale", line(cash, "DEBIT", 100), line(sales, "CREDIT", 100));
        postEntry("Second sale", line(cash, "DEBIT", 200), line(sales, "CREDIT", 200));

        mvc.perform(get("/accounts/{id}/postings", cash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Second sale"))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value(200))
                .andExpect(jsonPath("$[1].description").value("First sale"));
    }

    @Test
    void historyOfMissingAccountReturns404() throws Exception {
        mvc.perform(get("/accounts/{id}/postings", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    // --- reversals ---------------------------------------------------------

    @Test
    void reversalReturns201AndRestoresBalances() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long original = idOf(postEntry("Sale", line(cash, "DEBIT", 4_000), line(sales, "CREDIT", 4_000)));

        mvc.perform(post("/journal-entries/{id}/reversal", original).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/journal-entries/\\d+")))
                .andExpect(jsonPath("$.reversesEntryId").value(original))
                .andExpect(jsonPath("$.description").value("Reversal of entry " + original))
                .andExpect(jsonPath("$.postings[0].accountId").value(cash))
                .andExpect(jsonPath("$.postings[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.postings[1].direction").value("DEBIT"));

        assertThat(balanceOf(cash)).isZero();
        assertThat(balanceOf(sales)).isZero();
        assertThat(balanceOf(cash)).isEqualTo(sumOfPostings(cash));
    }

    @Test
    void originalEntryStaysInHistoryAfterReversal() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long original = idOf(postEntry("Sale", line(cash, "DEBIT", 4_000), line(sales, "CREDIT", 4_000)));

        mvc.perform(post("/journal-entries/{id}/reversal", original).header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated());

        mvc.perform(get("/journal-entries/{id}", original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postings[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.postings[0].amount").value(4_000));
        mvc.perform(get("/accounts/{id}/postings", cash))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Reversal of entry " + original))
                .andExpect(jsonPath("$[1].description").value("Sale"));
    }

    @Test
    void reversingTwiceReturns409AndChangesNothing() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long original = idOf(postEntry("Sale", line(cash, "DEBIT", 4_000), line(sales, "CREDIT", 4_000)));
        mvc.perform(post("/journal-entries/{id}/reversal", original).header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated());

        mvc.perform(post("/journal-entries/{id}/reversal", original).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Journal entry " + original + " has already been reversed"));

        assertThat(balanceOf(cash)).isZero();
        assertThat(postingCount(cash)).isEqualTo(2);
    }

    @Test
    void reversingAReversalReturns422() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long original = idOf(postEntry("Sale", line(cash, "DEBIT", 4_000), line(sales, "CREDIT", 4_000)));
        long reversal = idOf(mvc.perform(post("/journal-entries/{id}/reversal", original).header("Idempotency-Key", UUID.randomUUID().toString())));

        mvc.perform(post("/journal-entries/{id}/reversal", reversal).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Ledger rule violated"));
    }

    @Test
    void reversingMissingEntryReturns404() throws Exception {
        mvc.perform(post("/journal-entries/{id}/reversal", Long.MAX_VALUE).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void simultaneousReversalsPostExactlyOnce() throws Exception {
        long cash = createAccount("ASSET", "CAD");
        long sales = createAccount("REVENUE", "CAD");
        long original = idOf(postEntry("Sale", line(cash, "DEBIT", 4_000), line(sales, "CREDIT", 4_000)));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<String> attempt = () -> {
                    start.await();   // every thread waits here, then all go at once
                    try {
                        postingService.reverse(original);
                        return "reversed";
                    } catch (EntryAlreadyReversedException e) {
                        return "conflict";
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce("reversed");
            assertThat(outcomes).filteredOn("conflict"::equals).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(balanceOf(cash)).isZero();
        assertThat(postingCount(cash)).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    private long createAccount(String type, String currency) throws Exception {
        String body = """
                {"name": "%s-%s", "type": "%s", "currency": "%s"}
                """.formatted(type, UUID.randomUUID(), type, currency);
        return idOf(mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()));
    }

    private static String line(long accountId, String direction, long amount) {
        return """
                {"accountId": %d, "direction": "%s", "amount": %d}""".formatted(accountId, direction, amount);
    }

    private ResultActions postEntry(String description, String... lines) throws Exception {
        String body = """
                {"description": "%s", "postings": [%s]}
                """.formatted(description, String.join(", ", lines));
        return mvc.perform(post("/journal-entries").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private long postingCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, accountId);
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

    private static long idOf(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}

package com.jordanbikong.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jordanbikong.ledger.TestcontainersConfiguration;
import com.jordanbikong.ledger.journal.Direction;
import com.jordanbikong.ledger.journal.JournalEntryController;
import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.JournalEntryResponse;
import com.jordanbikong.ledger.journal.dto.PostingRequest;

/** Retries with the same Idempotency-Key must never move money twice. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JournalEntryController controller;

    @Test
    void retryWithSameKeyReplaysResponseAndPostsOnce() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();
        String body = entry("Sale", cash, sales, 2_500);

        String first = postEntry(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        String retry = postEntry(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/journal-entries/" + idOf(first)))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(retry, "$")).isEqualTo(JsonPath.read(first, "$"));
        assertThat(balanceOf(cash)).isEqualTo(2_500);
        assertThat(postingCount(cash)).isEqualTo(1);
    }

    @Test
    void retryThatOnlyDiffersInFormattingIsStillAReplay() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();

        postEntry(key, entry("Sale", cash, sales, 2_500)).andExpect(status().isCreated());

        // Same request, different whitespace and field order.
        String reformatted = """
                { "postings": [ {"amount": 2500, "direction": "DEBIT",  "accountId": %d},
                                {"amount": 2500, "direction": "CREDIT", "accountId": %d} ],
                  "description": "Sale" }
                """.formatted(cash, sales);
        postEntry(key, reformatted)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(postingCount(cash)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns409AndPostsNothing() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();
        postEntry(key, entry("Sale", cash, sales, 2_500)).andExpect(status().isCreated());

        postEntry(key, entry("Sale", cash, sales, 9_999))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key was already used for a different request"));

        assertThat(balanceOf(cash)).isEqualTo(2_500);
        assertThat(postingCount(cash)).isEqualTo(1);
    }

    @Test
    void sameKeyOnADifferentEndpointReturns409() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();
        long entryId = idOf(postEntry(key, entry("Sale", cash, sales, 2_500))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/journal-entries/{id}/reversal", entryId).header("Idempotency-Key", key))
                .andExpect(status().isConflict());

        assertThat(balanceOf(cash)).isEqualTo(2_500);
    }

    @Test
    void differentKeysPostTwice() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String body = entry("Sale", cash, sales, 2_500);

        postEntry(newKey(), body).andExpect(status().isCreated());
        postEntry(newKey(), body).andExpect(status().isCreated());

        assertThat(balanceOf(cash)).isEqualTo(5_000);
    }

    @Test
    void failedRequestDoesNotUseUpTheKey() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();
        String unbalanced = """
                {"description": "Sale", "postings": [
                  {"accountId": %d, "direction": "DEBIT", "amount": 2500},
                  {"accountId": %d, "direction": "CREDIT", "amount": 2400}]}
                """.formatted(cash, sales);

        postEntry(key, unbalanced).andExpect(status().isUnprocessableContent());
        // The 422 rolled back the key with everything else, so the fixed request goes through.
        postEntry(key, entry("Sale", cash, sales, 2_500))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        assertThat(balanceOf(cash)).isEqualTo(2_500);
    }

    @Test
    void reversalRetryReplaysInsteadOf409() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        long entryId = idOf(postEntry(newKey(), entry("Sale", cash, sales, 2_500))
                .andReturn().getResponse().getContentAsString());
        String key = newKey();

        String first = mvc.perform(post("/journal-entries/{id}/reversal", entryId).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/journal-entries/{id}/reversal", entryId).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(idOf(first)));

        assertThat(balanceOf(cash)).isZero();
        assertThat(postingCount(cash)).isEqualTo(2);
    }

    @Test
    void missingBlankOrTooLongKeyReturns400() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String body = entry("Sale", cash, sales, 2_500);

        mvc.perform(post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        postEntry("   ", body).andExpect(status().isBadRequest());
        postEntry("k".repeat(256), body).andExpect(status().isBadRequest());

        assertThat(postingCount(cash)).isZero();
    }

    @Test
    void simultaneousRequestsWithOneKeyPostOnce() throws Exception {
        long cash = createAccount("ASSET");
        long sales = createAccount("REVENUE");
        String key = newKey();
        CreateJournalEntryRequest request = new CreateJournalEntryRequest("Sale", List.of(
                new PostingRequest(cash, Direction.DEBIT, 2_500L),
                new PostingRequest(sales, Direction.CREDIT, 2_500L)));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<JournalEntryResponse>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();   // every thread waits here, then all go at once
                    return controller.create(key, request);
                }));
            }
            start.countDown();

            Set<Long> entryIds = new HashSet<>();
            int replays = 0;
            for (Future<ResponseEntity<JournalEntryResponse>> result : results) {
                ResponseEntity<JournalEntryResponse> response = result.get(30, TimeUnit.SECONDS);
                entryIds.add(response.getBody().id());
                if ("true".equals(response.getHeaders().getFirst("Idempotent-Replayed"))) {
                    replays++;
                }
            }
            assertThat(entryIds).as("every caller sees the same entry").hasSize(1);
            assertThat(replays).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(balanceOf(cash)).isEqualTo(2_500);
        assertThat(postingCount(cash)).isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------

    private long createAccount(String type) throws Exception {
        String body = """
                {"name": "%s-%s", "type": "%s", "currency": "CAD"}
                """.formatted(type, UUID.randomUUID(), type);
        return idOf(mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String entry(String description, long debitAccount, long creditAccount, long amount) {
        return """
                {"description": "%s", "postings": [
                  {"accountId": %d, "direction": "DEBIT", "amount": %d},
                  {"accountId": %d, "direction": "CREDIT", "amount": %d}]}
                """.formatted(description, debitAccount, amount, creditAccount, amount);
    }

    private ResultActions postEntry(String key, String body) throws Exception {
        return mvc.perform(post("/journal-entries").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private long postingCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, accountId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static long idOf(String json) {
        Number id = JsonPath.read(json, "$.id");
        return id.longValue();
    }
}

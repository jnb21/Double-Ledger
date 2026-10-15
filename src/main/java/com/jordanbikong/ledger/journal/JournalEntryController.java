package com.jordanbikong.ledger.journal;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jordanbikong.ledger.idempotency.IdempotencyService;
import com.jordanbikong.ledger.idempotency.IdempotencyService.Outcome;
import com.jordanbikong.ledger.idempotency.RequestFingerprint;
import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.JournalEntryResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PostingService service;
    private final IdempotencyService idempotency;
    private final RequestFingerprint fingerprints;

    public JournalEntryController(PostingService service, IdempotencyService idempotency,
            RequestFingerprint fingerprints) {
        this.service = service;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
    }

    @PostMapping
    public ResponseEntity<JournalEntryResponse> create(
            @RequestHeader(IdempotencyService.HEADER) String key,
            @Valid @RequestBody CreateJournalEntryRequest request) {
        String fingerprint = fingerprints.of("POST /journal-entries", request);
        Outcome<JournalEntryResponse> outcome = idempotency.execute(key, fingerprint, JournalEntryResponse.class,
                () -> JournalEntryResponse.from(service.post(request)));
        return created(outcome);
    }

    @PostMapping("/{id}/reversal")
    public ResponseEntity<JournalEntryResponse> reverse(
            @RequestHeader(IdempotencyService.HEADER) String key,
            @PathVariable long id) {
        String fingerprint = fingerprints.of("POST /journal-entries/" + id + "/reversal", null);
        Outcome<JournalEntryResponse> outcome = idempotency.execute(key, fingerprint, JournalEntryResponse.class,
                () -> JournalEntryResponse.from(service.reverse(id)));
        return created(outcome);
    }

    @GetMapping("/{id}")
    public JournalEntryResponse get(@PathVariable long id) {
        return JournalEntryResponse.from(service.get(id));
    }

    /** A replay returns the same status, Location and body as the original response. */
    private static ResponseEntity<JournalEntryResponse> created(Outcome<JournalEntryResponse> outcome) {
        JournalEntryResponse body = outcome.body();
        return ResponseEntity.created(URI.create("/journal-entries/" + body.id()))
                .header(REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(body);
    }
}

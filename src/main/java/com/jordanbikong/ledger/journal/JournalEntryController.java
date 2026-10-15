package com.jordanbikong.ledger.journal;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.JournalEntryResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    private final PostingService service;

    public JournalEntryController(PostingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JournalEntryResponse> create(@Valid @RequestBody CreateJournalEntryRequest request) {
        JournalEntryResponse created = JournalEntryResponse.from(service.post(request));
        return ResponseEntity.created(URI.create("/journal-entries/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public JournalEntryResponse get(@PathVariable long id) {
        return JournalEntryResponse.from(service.get(id));
    }
}

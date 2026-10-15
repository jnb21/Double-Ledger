package com.jordanbikong.ledger.journal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.jordanbikong.ledger.journal.dto.AccountPostingResponse;

/** An account's statement. Lives in journal/ because the data is postings. */
@RestController
public class AccountHistoryController {

    private final AccountHistoryService service;

    public AccountHistoryController(AccountHistoryService service) {
        this.service = service;
    }

    @GetMapping("/accounts/{id}/postings")
    public List<AccountPostingResponse> history(@PathVariable long id) {
        return service.history(id);
    }
}

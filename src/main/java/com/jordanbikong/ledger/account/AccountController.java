package com.jordanbikong.ledger.account;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jordanbikong.ledger.account.dto.AccountResponse;
import com.jordanbikong.ledger.account.dto.CreateAccountRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = AccountResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/accounts/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.from(service.get(id));
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list().stream().map(AccountResponse::from).toList();
    }
}

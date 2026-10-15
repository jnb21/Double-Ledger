package com.jordanbikong.ledger.journal;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jordanbikong.ledger.account.AccountService;
import com.jordanbikong.ledger.journal.dto.AccountPostingResponse;

@Service
public class AccountHistoryService {

    private final AccountService accounts;
    private final PostingRepository postings;

    public AccountHistoryService(AccountService accounts, PostingRepository postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    /** 404 for an unknown account, so it isn't confused with an account that has no postings yet. */
    @Transactional(readOnly = true)
    public List<AccountPostingResponse> history(long accountId) {
        accounts.get(accountId);
        return postings.findHistory(accountId);
    }
}

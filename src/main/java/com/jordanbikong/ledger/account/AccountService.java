package com.jordanbikong.ledger.account;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jordanbikong.ledger.account.dto.CreateAccountRequest;

@Service
public class AccountService {

    /** Postgres's default name for the UNIQUE constraint on accounts.name. */
    private static final String UNIQUE_NAME_CONSTRAINT = "accounts_name_key";

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Relies on the database's UNIQUE constraint instead of checking first:
     * a "does it exist?" query followed by an insert would let two concurrent
     * requests both pass the check.
     */
    @Transactional
    public Account create(CreateAccountRequest request) {
        try {
            return accounts.saveAndFlush(new Account(request.name(), request.type(), request.currency(),
                    Boolean.TRUE.equals(request.allowNegative())));
        } catch (DataIntegrityViolationException e) {
            if (isUniqueNameViolation(e)) {
                throw new DuplicateAccountNameException(request.name(), e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAllByOrderByIdAsc();
    }

    private static boolean isUniqueNameViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && UNIQUE_NAME_CONSTRAINT.equals(cve.getConstraintName());
    }
}

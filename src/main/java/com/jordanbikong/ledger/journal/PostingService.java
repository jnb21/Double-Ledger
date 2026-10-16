package com.jordanbikong.ledger.journal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jordanbikong.ledger.account.Account;
import com.jordanbikong.ledger.account.AccountNotFoundException;
import com.jordanbikong.ledger.account.AccountRepository;
import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.PostingRequest;

/**
 * Records journal entries. Each public write method runs in one transaction:
 * either the entry, its postings and every balance change are saved, or
 * nothing is. The V1 trigger re-checks the balance rule at commit.
 *
 * Lock order, the same on every write path: idempotency key (taken by the
 * caller), then accounts in id order. A fixed global order makes deadlocks
 * impossible.
 */
@Service
public class PostingService {

    /** Postgres's default name for the UNIQUE constraint on journal_entries.reverses_entry_id. */
    private static final String UNIQUE_REVERSAL_CONSTRAINT = "journal_entries_reverses_entry_id_key";

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;

    public PostingService(JournalEntryRepository entries, AccountRepository accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    @Transactional
    public JournalEntry post(CreateJournalEntryRequest request) {
        List<PostingRequest> lines = request.postings();

        requireBalanced(lines);
        Map<Long, Account> accountsById = lockAccounts(lines.stream().map(PostingRequest::accountId).toList());
        requireSingleCurrency(accountsById.values());

        JournalEntry entry = new JournalEntry(request.description());
        for (PostingRequest line : lines) {
            entry.addPosting(line.accountId(), line.direction(), line.amount());
        }
        SortedMap<Long, Long> changes = balanceChanges(entry, accountsById);
        requireNoOverdraft(changes, accountsById);

        entries.save(entry);
        changes.forEach(accounts::addToBalance);
        return entry;
    }

    /**
     * Cancels an entry by posting its mirror image. The original stays in
     * history untouched; the two together net to zero.
     */
    @Transactional
    public JournalEntry reverse(long id) {
        JournalEntry original = get(id);
        if (original.isReversal()) {
            throw new LedgerRuleException(
                    "Journal entry " + id + " is itself a reversal and cannot be reversed; post a new entry instead");
        }

        JournalEntry reversal = original.reversal();
        Map<Long, Account> accountsById = lockAccounts(
                reversal.getPostings().stream().map(Posting::getAccountId).toList());

        // No "already reversed?" query first: two concurrent requests could
        // both pass it. The UNIQUE constraint lets exactly one insert win.
        // Inserted before the overdraft check, so a duplicate gets 409 rather
        // than a misleading "insufficient funds".
        try {
            entries.saveAndFlush(reversal);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, UNIQUE_REVERSAL_CONSTRAINT)) {
                throw new EntryAlreadyReversedException(id, e);
            }
            throw e;
        }

        SortedMap<Long, Long> changes = balanceChanges(reversal, accountsById);
        requireNoOverdraft(changes, accountsById);
        changes.forEach(accounts::addToBalance);
        return reversal;
    }

    @Transactional(readOnly = true)
    public JournalEntry get(long id) {
        return entries.findWithPostingsById(id).orElseThrow(() -> new JournalEntryNotFoundException(id));
    }

    private static void requireBalanced(List<PostingRequest> lines) {
        long debits = 0;
        long credits = 0;
        try {
            for (PostingRequest line : lines) {
                if (line.direction() == Direction.DEBIT) {
                    debits = Math.addExact(debits, line.amount());
                } else {
                    credits = Math.addExact(credits, line.amount());
                }
            }
        } catch (ArithmeticException e) {
            throw new LedgerRuleException("Posting amounts are too large");
        }
        if (debits != credits) {
            throw new LedgerRuleException(
                    "Entry is unbalanced: debits " + debits + " != credits " + credits);
        }
    }

    /**
     * Loads and locks every account the entry touches. Once this returns, no
     * other transaction can change these balances until we commit, so the
     * balances read here are the ones the overdraft check can trust.
     */
    private Map<Long, Account> lockAccounts(Collection<Long> accountIds) {
        Set<Long> ids = new TreeSet<>(accountIds);
        Map<Long, Account> found = accounts.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (Long id : ids) {
            if (!found.containsKey(id)) {
                throw new AccountNotFoundException(id);
            }
        }
        return found;
    }

    private static void requireSingleCurrency(Iterable<Account> involved) {
        Set<String> currencies = new TreeSet<>();
        involved.forEach(account -> currencies.add(account.getCurrency()));
        if (currencies.size() > 1) {
            throw new LedgerRuleException("Entry mixes currencies: " + String.join(", ", currencies));
        }
    }

    /**
     * Net change per account, on each account's normal side: a debit raises
     * an ASSET or EXPENSE balance and lowers the others; a credit does the reverse.
     * Sorted by account id, so the updates also run in lock order.
     */
    private static SortedMap<Long, Long> balanceChanges(JournalEntry entry, Map<Long, Account> accountsById) {
        SortedMap<Long, Long> changes = new TreeMap<>();
        for (Posting posting : entry.getPostings()) {
            boolean debitNormal = accountsById.get(posting.getAccountId()).getType().isDebitNormal();
            boolean raises = (posting.getDirection() == Direction.DEBIT) == debitNormal;
            long delta = raises ? posting.getAmount() : -posting.getAmount();
            changes.merge(posting.getAccountId(), delta, Math::addExact);
        }
        return changes;
    }

    /** Uses the balances read under lock, so a concurrent posting can't slip in between check and write. */
    private static void requireNoOverdraft(Map<Long, Long> changes, Map<Long, Account> accountsById) {
        changes.forEach((accountId, delta) -> {
            Account account = accountsById.get(accountId);
            long newBalance;
            try {
                newBalance = Math.addExact(account.getBalance(), delta);
            } catch (ArithmeticException e) {
                throw new LedgerRuleException("Balance of account " + accountId + " would be too large");
            }
            if (newBalance < 0 && !account.isAllowNegative()) {
                throw new LedgerRuleException("Insufficient funds in account " + accountId
                        + ": balance " + account.getBalance() + ", change " + delta);
            }
        });
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        return e.getCause() instanceof ConstraintViolationException cve
                && constraint.equals(cve.getConstraintName());
    }
}

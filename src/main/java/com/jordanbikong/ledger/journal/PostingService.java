package com.jordanbikong.ledger.journal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jordanbikong.ledger.account.Account;
import com.jordanbikong.ledger.account.AccountNotFoundException;
import com.jordanbikong.ledger.account.AccountRepository;
import com.jordanbikong.ledger.journal.dto.CreateJournalEntryRequest;
import com.jordanbikong.ledger.journal.dto.PostingRequest;

/**
 * Records journal entries. Everything in post() runs in one transaction:
 * either the entry, its postings and every balance change are saved, or
 * nothing is. The V1 trigger re-checks the balance rule at commit.
 */
@Service
public class PostingService {

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
        Map<Long, Account> accountsById = loadAccounts(lines);
        requireSingleCurrency(accountsById.values());

        JournalEntry entry = new JournalEntry(request.description());
        for (PostingRequest line : lines) {
            entry.addPosting(line.accountId(), line.direction(), line.amount());
        }
        entries.save(entry);

        // Sorted by account id: the same order every time, which the
        // concurrency stage relies on to avoid deadlocks.
        balanceChanges(lines, accountsById).forEach(accounts::addToBalance);
        return entry;
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

    private Map<Long, Account> loadAccounts(List<PostingRequest> lines) {
        Set<Long> ids = lines.stream().map(PostingRequest::accountId).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, Account> found = accounts.findAllById(ids).stream()
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
     */
    private static SortedMap<Long, Long> balanceChanges(List<PostingRequest> lines, Map<Long, Account> accountsById) {
        SortedMap<Long, Long> changes = new TreeMap<>();
        for (PostingRequest line : lines) {
            boolean debitNormal = accountsById.get(line.accountId()).getType().isDebitNormal();
            boolean raises = (line.direction() == Direction.DEBIT) == debitNormal;
            long delta = raises ? line.amount() : -line.amount();
            changes.merge(line.accountId(), delta, Math::addExact);
        }
        return changes;
    }
}

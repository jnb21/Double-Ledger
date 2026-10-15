package com.jordanbikong.ledger.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.Generated;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** One business event, made of balanced postings. Append-only: no setters. */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "reverses_entry_id", unique = true)
    private Long reversesEntryId;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Posting> postings = new ArrayList<>();

    /** Required by JPA. */
    protected JournalEntry() {
    }

    public JournalEntry(String description) {
        this.description = description;
    }

    /**
     * A new entry that cancels this one: the same lines with each direction
     * flipped, so every balance returns to where it was before.
     */
    public JournalEntry reversal() {
        JournalEntry reversal = new JournalEntry("Reversal of entry " + id);
        reversal.reversesEntryId = id;
        for (Posting posting : postings) {
            reversal.addPosting(posting.getAccountId(), posting.getDirection().opposite(), posting.getAmount());
        }
        return reversal;
    }

    public boolean isReversal() {
        return reversesEntryId != null;
    }

    public void addPosting(long accountId, Direction direction, long amount) {
        postings.add(new Posting(this, accountId, direction, amount));
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Long getReversesEntryId() {
        return reversesEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Posting> getPostings() {
        return List.copyOf(postings);
    }
}

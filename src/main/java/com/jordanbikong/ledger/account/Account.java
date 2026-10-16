package com.jordanbikong.ledger.account;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A ledger account: a named bucket of money in one currency. Maps the V1 accounts table. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    /** Minor units (cents), on the account's normal side. Changed only by posting. */
    @Column(nullable = false)
    private long balance;

    /** If false, posting may never take the balance below zero (V2 CHECK constraint). */
    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    /** Set by the database (DEFAULT now()) and read back after insert. */
    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Account() {
    }

    public Account(String name, AccountType type, String currency, boolean allowNegative) {
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.allowNegative = allowNegative;
        this.balance = 0;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

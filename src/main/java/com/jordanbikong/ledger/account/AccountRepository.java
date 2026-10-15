package com.jordanbikong.ledger.account;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByOrderByIdAsc();

    /**
     * Atomic in Postgres: the database reads and writes the balance in one
     * statement, so two concurrent postings can't overwrite each other's change.
     */
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :delta WHERE a.id = :id")
    int addToBalance(long id, long delta);
}

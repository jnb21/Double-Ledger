package com.jordanbikong.ledger.account;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByOrderByIdAsc();

    /**
     * SELECT ... ORDER BY id FOR UPDATE: locks the rows until the transaction
     * ends, so a concurrent posting waits and then reads the real balance.
     * Locking in id order means two transactions can never wait on each
     * other in a cycle, so they can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id")
    List<Account> findAllByIdForUpdate(Collection<Long> ids);

    /**
     * Atomic in Postgres: the database reads and writes the balance in one
     * statement, so two concurrent postings can't overwrite each other's change.
     */
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :delta WHERE a.id = :id")
    int addToBalance(long id, long delta);
}

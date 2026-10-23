package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Each creation attempt must finish (or roll back) before a collision is retried.
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    default Account saveNewAccount(Account account) {
        return saveAndFlush(account);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Query("select a.customer.id from Account a where a.id = :id")
    Optional<UUID> findCustomerIdByAccountId(@Param("id") UUID id);

    boolean existsByAccountNumber(String accountNumber);
    List<Account> findByCustomerId(UUID customerId);
}

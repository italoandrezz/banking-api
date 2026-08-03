package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByOriginAccountIdOrDestinationAccountId(
            UUID originAccountId,
            UUID destinationAccountId
    );
}
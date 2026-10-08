package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.italo.bankingapi.enums.TransactionType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {
    // Lock only the original row. Loading its Account entities here would cache
    // stale balances before the account locks are acquired.
    @Query(value = """
            SELECT id AS "id", account_id AS "originAccountId",
                   destination_account_id AS "destinationAccountId", type::text AS "type",
                   amount AS "amount", original_transaction_id AS "originalTransactionId"
            FROM transactions WHERE id = :id FOR UPDATE
            """, nativeQuery = true)
    Optional<OriginalTransaction> findOriginalForUpdate(@Param("id") UUID id);

    boolean existsByOriginalTransactionId(UUID originalTransactionId);

    @Query("select t.id as id, t.originalTransactionId as originalTransactionId from Transaction t where t.originalTransactionId in :ids")
    List<ReversalLink> findReversalLinks(@Param("ids") List<UUID> ids);

    interface ReversalLink {
        UUID getId();
        UUID getOriginalTransactionId();
    }

    interface OriginalTransaction {
        UUID getId();
        UUID getOriginAccountId();
        UUID getDestinationAccountId();
        String getType();
        BigDecimal getAmount();
        UUID getOriginalTransactionId();
    }

    default Page<Transaction> findStatement(UUID accountId, LocalDateTime start, LocalDateTime end,
                                            TransactionType type, Pageable pageable) {
        return findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // LEFT JOIN keeps deposits/withdrawals, whose destination is null.
            var destination = root.join("destinationAccount", JoinType.LEFT);
            predicates.add(cb.or(cb.equal(root.get("originAccount").get("id"), accountId),
                    cb.equal(destination.get("id"), accountId)));
            if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            if (end != null) predicates.add(cb.lessThan(root.get("createdAt"), end));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }
}

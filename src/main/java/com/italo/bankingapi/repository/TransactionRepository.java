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

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {
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

package com.italo.bankingapi.service;

import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.transaction.TransactionResponse;
import com.italo.bankingapi.dto.transaction.TransactionPageResponse;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.InvalidStatementQueryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final AuthenticatedCustomer authenticatedCustomer;

    @Transactional(readOnly = true)
    public TransactionPageResponse findTransactionsByAccountId(UUID accountId, int page, int size,
                                                               LocalDate startDate, LocalDate endDate,
                                                               TransactionType type) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidStatementQueryException("Page must be nonnegative, size must be between 1 and 100, and offset must not exceed 2147483647.");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidStatementQueryException("Start date must not be after end date.");
        }
        if (LocalDate.MAX.equals(endDate)) { throw new InvalidStatementQueryException("End date is out of range."); }
        Account account = findAccountOrThrow(accountId);
        authenticatedCustomer.requireOwner(account.getCustomer().getId());
        var transactions = transactionRepository.findStatement(account.getId(),
                startDate == null ? null : startDate.atStartOfDay(),
                endDate == null ? null : endDate.plusDays(1).atStartOfDay(), type,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var ids = transactions.getContent().stream().map(Transaction::getId).toList();
        Map<UUID, UUID> reversalIds = ids.isEmpty() ? Map.of() : transactionRepository.findReversalLinks(ids).stream()
                .collect(Collectors.toMap(TransactionRepository.ReversalLink::getOriginalTransactionId, TransactionRepository.ReversalLink::getId));
        return new TransactionPageResponse(transactions.getContent().stream().map(t -> toTransactionResponse(t, reversalIds.get(t.getId()))).toList(),
                page, size, transactions.getTotalElements(), transactions.getTotalPages());
    }
    private Account findAccountOrThrow(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Account not found."));
    }
    private TransactionResponse toTransactionResponse(Transaction transaction, UUID reversalId) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .originAccountId(transaction.getOriginAccount().getId())
                .destinationAccountId(
                        transaction.getDestinationAccount() != null
                                ? transaction.getDestinationAccount().getId()
                                : null
                )
                .type(transaction.getType())
                .amount(transaction.getAmount())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .originalTransactionId(transaction.getOriginalTransactionId())
                .reversalTransactionId(reversalId)
                .build();
    }
}

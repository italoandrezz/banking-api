package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.transaction.TransactionResponse;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public List<TransactionResponse> findTransactionsByAccountId(UUID accountId) {
        Account account = findAccountOrThrow(accountId);
        List<Transaction> transactions = transactionRepository
                        .findByOriginAccountIdOrDestinationAccountId(
                                account.getId(),
                                account.getId()
                        );
        return transactions.stream().map(this::toTransactionResponse).toList();
    }
    private Account findAccountOrThrow(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Account not found."));
    }
    private TransactionResponse toTransactionResponse(Transaction transaction) {
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
                .build();
    }
}
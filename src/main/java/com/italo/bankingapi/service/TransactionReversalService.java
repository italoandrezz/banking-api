package com.italo.bankingapi.service;

import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.transaction.ReversalRequest;
import com.italo.bankingapi.dto.transaction.ReversalResponse;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.InvalidReversalException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionReversalService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999.99");
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final AuthenticatedCustomer authenticatedCustomer;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ReversalResponse reverse(UUID id, ReversalRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 255) {
            throw new InvalidReversalException("Reversal reason must contain 1 to 255 characters and must not be blank.");
        }
        UUID adminId = authenticatedCustomer.getCustomer().getId();
        var original = transactions.findOriginalForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
        if (original.getOriginalTransactionId() != null) {
            throw new ConflictException("A reversal transaction cannot be reversed.");
        }
        if (transactions.existsByOriginalTransactionId(id)) {
            throw new ConflictException("Transaction has already been reversed.");
        }
        UUID originId = original.getOriginAccountId();
        UUID destinationId = original.getDestinationAccountId();
        // Same UUID ordering as ordinary transfers: no opposing account-lock cycle.
        boolean originFirst = destinationId == null || originId.compareTo(destinationId) <= 0;
        Account first = lockAccount(originFirst ? originId : destinationId);
        Account second = destinationId == null ? null : lockAccount(originFirst ? destinationId : originId);
        Account origin = originFirst ? first : second;
        Account destination = destinationId == null ? null : (originFirst ? second : first);
        requireActive(origin);
        if (destination != null) requireActive(destination);

        TransactionType originalType = TransactionType.valueOf(original.getType());
        BigDecimal amount = original.getAmount();
        Account debit = null;
        Account credit = null;
        TransactionType inverseType;
        switch (originalType) {
            case DEPOSIT -> { debit = origin; inverseType = TransactionType.WITHDRAW; }
            case WITHDRAW -> { credit = origin; inverseType = TransactionType.DEPOSIT; }
            case TRANSFER -> {
                if (destination == null || originId.equals(destinationId)) {
                    throw new ConflictException("Original transfer has invalid accounts.");
                }
                debit = destination; credit = origin; inverseType = TransactionType.TRANSFER;
            }
            default -> throw new ConflictException("Unsupported transaction type.");
        }
        if (debit != null && debit.getBalance().compareTo(amount) < 0) {
            throw new ConflictException("Insufficient balance to reverse transaction.");
        }
        if (credit != null && credit.getBalance().add(amount).compareTo(MAX_BALANCE) > 0) {
            throw new ConflictException("Reversal would exceed the maximum account balance.");
        }
        if (debit != null) debit.setBalance(debit.getBalance().subtract(amount));
        if (credit != null) credit.setBalance(credit.getBalance().add(amount));
        Transaction reversal = transactions.saveAndFlush(Transaction.builder()
                .originAccount(inverseType == TransactionType.TRANSFER ? debit : origin)
                .destinationAccount(inverseType == TransactionType.TRANSFER ? credit : null)
                .type(inverseType).amount(amount).description("Reversal of transaction " + id)
                .createdAt(LocalDateTime.now()).originalTransactionId(id)
                .reversalAdminId(adminId).reversalReason(request.reason().strip()).build());
        return new ReversalResponse(reversal.getId(), id, inverseType, reversal.getOriginAccount().getId(),
                reversal.getDestinationAccount() == null ? null : reversal.getDestinationAccount().getId(),
                amount, adminId, reversal.getReversalReason(), reversal.getCreatedAt());
    }

    private Account lockAccount(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Account not found."));
    }

    private void requireActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ConflictException("Accounts must be ACTIVE to reverse a transaction.");
        }
    }
}

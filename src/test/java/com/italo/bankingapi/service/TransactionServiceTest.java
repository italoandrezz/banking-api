package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.transaction.TransactionResponse;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void shouldReturnDepositHistoryForExistingAccount() {
        // Arrange
        Account account = createAccount();
        Transaction transaction = createTransaction(TransactionType.DEPOSIT, account, null);
        prepareHistory(account, List.of(transaction));

        // Act
        List<TransactionResponse> responses = transactionService.findTransactionsByAccountId(account.getId());

        // Assert
        assertEquals(1, responses.size());
        assertTransactionResponse(responses.get(0), transaction);
        assertNull(responses.get(0).getDestinationAccountId());
        verifyHistoryQuery(account);
    }

    @Test
    void shouldReturnWithdrawalHistoryForExistingAccount() {
        // Arrange
        Account account = createAccount();
        Transaction transaction = createTransaction(TransactionType.WITHDRAW, account, null);
        prepareHistory(account, List.of(transaction));

        // Act
        List<TransactionResponse> responses = transactionService.findTransactionsByAccountId(account.getId());

        // Assert
        assertEquals(TransactionType.WITHDRAW, responses.get(0).getType());
        assertTransactionResponse(responses.get(0), transaction);
        verifyHistoryQuery(account);
    }

    @Test
    void shouldReturnTransferHistoryWithOriginAndDestination() {
        // Arrange
        Account origin = createAccount();
        Account destination = createAccount();
        Transaction transaction = createTransaction(TransactionType.TRANSFER, origin, destination);
        prepareHistory(origin, List.of(transaction));

        // Act
        List<TransactionResponse> responses = transactionService.findTransactionsByAccountId(origin.getId());

        // Assert
        assertTransactionResponse(responses.get(0), transaction);
        assertEquals(origin.getId(), responses.get(0).getOriginAccountId());
        assertEquals(destination.getId(), responses.get(0).getDestinationAccountId());
        verifyHistoryQuery(origin);
    }

    @Test
    void shouldReturnEmptyHistoryWhenAccountHasNoTransactions() {
        // Arrange
        Account account = createAccount();
        prepareHistory(account, List.of());

        // Act
        List<TransactionResponse> responses = transactionService.findTransactionsByAccountId(account.getId());

        // Assert
        assertEquals(0, responses.size());
        verifyHistoryQuery(account);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenAccountDoesNotExist() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> transactionService.findTransactionsByAccountId(accountId)
        );

        // Assert
        assertEquals("Account not found.", exception.getMessage());
        verify(transactionRepository, never())
                .findByOriginAccountIdOrDestinationAccountId(accountId, accountId);
    }

    private void prepareHistory(Account account, List<Transaction> transactions) {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(transactionRepository.findByOriginAccountIdOrDestinationAccountId(account.getId(), account.getId()))
                .thenReturn(transactions);
    }

    private void verifyHistoryQuery(Account account) {
        verify(accountRepository, times(1)).findById(account.getId());
        verify(transactionRepository, times(1))
                .findByOriginAccountIdOrDestinationAccountId(account.getId(), account.getId());
    }

    private Account createAccount() {
        return Account.builder().id(UUID.randomUUID()).build();
    }

    private Transaction createTransaction(TransactionType type, Account origin, Account destination) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .originAccount(origin)
                .destinationAccount(destination)
                .type(type)
                .amount(new BigDecimal("50.00"))
                .description("Test transaction")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void assertTransactionResponse(TransactionResponse response, Transaction transaction) {
        assertEquals(transaction.getId(), response.getId());
        assertEquals(transaction.getOriginAccount().getId(), response.getOriginAccountId());
        assertEquals(transaction.getType(), response.getType());
        assertEquals(transaction.getAmount(), response.getAmount());
        assertEquals(transaction.getDescription(), response.getDescription());
        assertEquals(transaction.getCreatedAt(), response.getCreatedAt());
    }
}

package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.AccountResponse;
import com.italo.bankingapi.dto.account.CreateAccountRequest;
import com.italo.bankingapi.dto.account.DepositRequest;
import com.italo.bankingapi.dto.account.TransferRequest;
import com.italo.bankingapi.dto.account.WithdrawRequest;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.InsufficientBalanceException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void shouldCreateAccountSuccessfully() {
        // Arrange
        Customer customer = createCustomer();
        CreateAccountRequest request = new CreateAccountRequest(customer.getId());
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(UUID.randomUUID());
            return account;
        });

        // Act
        AccountResponse response = accountService.createAccount(request);

        // Assert
        assertNotNull(response.getId());
        assertEquals(customer.getId(), response.getCustomerId());
        assertEquals("0001", response.getAgency());
        assertEquals(BigDecimal.ZERO, response.getBalance());
        assertEquals(AccountStatus.ACTIVE, response.getStatus());
        assertNotNull(response.getCreatedAt());
        assertEquals(8, response.getAccountNumber().length());
        verify(accountRepository, times(1)).existsByAccountNumber(response.getAccountNumber());
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void shouldGenerateAnotherAccountNumberWhenCollisionOccurs() {
        // Arrange
        Customer customer = createCustomer();
        CreateAccountRequest request = new CreateAccountRequest(customer.getId());
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(accountRepository.existsByAccountNumber(any())).thenReturn(true, false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(UUID.randomUUID());
            return account;
        });

        // Act
        AccountResponse response = accountService.createAccount(request);

        // Assert
        assertNotNull(response.getAccountNumber());
        verify(accountRepository, times(2)).existsByAccountNumber(any());
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void shouldThrowNotFoundExceptionWhenCreatingAccountForNonexistentCustomer() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        CreateAccountRequest request = new CreateAccountRequest(customerId);
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> accountService.createAccount(request)
        );

        // Assert
        assertEquals("Customer not found.", exception.getMessage());
        verify(accountRepository, never()).existsByAccountNumber(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void shouldFindAccountByIdSuccessfully() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // Act
        AccountResponse response = accountService.findAccountById(account.getId());

        // Assert
        assertAccountResponse(response, account);
        verify(accountRepository, times(1)).findById(account.getId());
    }

    @Test
    void shouldThrowNotFoundExceptionWhenAccountDoesNotExist() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> accountService.findAccountById(accountId)
        );

        // Assert
        assertEquals("Account not found.", exception.getMessage());
        verify(accountRepository, times(1)).findById(accountId);
    }

    @Test
    void shouldListAllAccountsSuccessfully() {
        // Arrange
        Account firstAccount = createAccount(new BigDecimal("100.00"));
        Account secondAccount = createAccount(new BigDecimal("200.00"));
        when(accountRepository.findAll()).thenReturn(List.of(firstAccount, secondAccount));

        // Act
        List<AccountResponse> responses = accountService.findAllAccounts();

        // Assert
        assertEquals(2, responses.size());
        assertAccountResponse(responses.get(0), firstAccount);
        assertAccountResponse(responses.get(1), secondAccount);
        verify(accountRepository, times(1)).findAll();
    }

    @Test
    void shouldDepositSuccessfully() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        DepositRequest request = new DepositRequest(new BigDecimal("50.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        // Act
        AccountResponse response = accountService.deposit(account.getId(), request);

        // Assert
        assertEquals(new BigDecimal("150.00"), response.getBalance());
        Transaction transaction = captureTransaction();
        assertTransaction(transaction, TransactionType.DEPOSIT, request.getAmount(), account, null);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDepositingIntoNonexistentAccount() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        DepositRequest request = new DepositRequest(new BigDecimal("50.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> accountService.deposit(accountId, request)
        );

        // Assert
        assertEquals("Account not found.", exception.getMessage());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldWithdrawSuccessfully() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        WithdrawRequest request = new WithdrawRequest(new BigDecimal("40.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        // Act
        AccountResponse response = accountService.withdraw(account.getId(), request);

        // Assert
        assertEquals(new BigDecimal("60.00"), response.getBalance());
        Transaction transaction = captureTransaction();
        assertTransaction(transaction, TransactionType.WITHDRAW, request.getAmount(), account, null);
    }

    @Test
    void shouldLeaveZeroBalanceWhenWithdrawingEntireBalance() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        WithdrawRequest request = new WithdrawRequest(new BigDecimal("100.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        // Act
        AccountResponse response = accountService.withdraw(account.getId(), request);

        // Assert
        assertEquals(new BigDecimal("0.00"), response.getBalance());
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    void shouldThrowInsufficientBalanceExceptionWhenWithdrawalExceedsBalance() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        WithdrawRequest request = new WithdrawRequest(new BigDecimal("100.01"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // Act
        InsufficientBalanceException exception = assertThrows(
                InsufficientBalanceException.class,
                () -> accountService.withdraw(account.getId(), request)
        );

        // Assert
        assertEquals("Insufficient balance.", exception.getMessage());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldTransferSuccessfully() {
        // Arrange
        Account source = createAccount(new BigDecimal("100.00"));
        Account destination = createAccount(new BigDecimal("20.00"));
        TransferRequest request = new TransferRequest(source.getId(), destination.getId(), new BigDecimal("40.00"));
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId())).thenReturn(Optional.of(destination));

        // Act
        AccountResponse response = accountService.transfer(request);

        // Assert
        assertEquals(new BigDecimal("60.00"), response.getBalance());
        assertEquals(new BigDecimal("60.00"), destination.getBalance());
        verify(accountRepository, times(1)).save(source);
        verify(accountRepository, times(1)).save(destination);
        Transaction transaction = captureTransaction();
        assertTransaction(transaction, TransactionType.TRANSFER, request.getAmount(), source, destination);
    }

    @Test
    void shouldThrowConflictExceptionWhenTransferringToSameAccount() {
        // Arrange
        Account account = createAccount(new BigDecimal("100.00"));
        TransferRequest request = new TransferRequest(account.getId(), account.getId(), new BigDecimal("40.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // Act
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> accountService.transfer(request)
        );

        // Assert
        assertEquals("Source and destination accounts must be different.", exception.getMessage());
        verify(accountRepository, times(2)).findById(account.getId());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldThrowNotFoundExceptionWhenSourceAccountDoesNotExist() {
        // Arrange
        UUID sourceId = UUID.randomUUID();
        TransferRequest request = new TransferRequest(sourceId, UUID.randomUUID(), new BigDecimal("40.00"));
        when(accountRepository.findById(sourceId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> accountService.transfer(request)
        );

        // Assert
        assertEquals("Account not found.", exception.getMessage());
        verify(accountRepository, never()).findById(request.getDestinationAccountId());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDestinationAccountDoesNotExist() {
        // Arrange
        Account source = createAccount(new BigDecimal("100.00"));
        UUID destinationId = UUID.randomUUID();
        TransferRequest request = new TransferRequest(source.getId(), destinationId, new BigDecimal("40.00"));
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> accountService.transfer(request)
        );

        // Assert
        assertEquals("Account not found.", exception.getMessage());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldThrowInsufficientBalanceExceptionWhenTransferExceedsBalance() {
        // Arrange
        Account source = createAccount(new BigDecimal("30.00"));
        Account destination = createAccount(new BigDecimal("20.00"));
        TransferRequest request = new TransferRequest(source.getId(), destination.getId(), new BigDecimal("40.00"));
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId())).thenReturn(Optional.of(destination));

        // Act
        InsufficientBalanceException exception = assertThrows(
                InsufficientBalanceException.class,
                () -> accountService.transfer(request)
        );

        // Assert
        assertEquals("Insufficient balance.", exception.getMessage());
        assertEquals(new BigDecimal("30.00"), source.getBalance());
        assertEquals(new BigDecimal("20.00"), destination.getBalance());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    private Customer createCustomer() {
        return Customer.builder().id(UUID.randomUUID()).build();
    }

    private Account createAccount(BigDecimal balance) {
        return Account.builder()
                .id(UUID.randomUUID())
                .customer(createCustomer())
                .accountNumber("12345678")
                .agency("0001")
                .balance(balance)
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Transaction captureTransaction() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private void assertTransaction(
            Transaction transaction,
            TransactionType type,
            BigDecimal amount,
            Account origin,
            Account destination
    ) {
        assertEquals(type, transaction.getType());
        assertEquals(amount, transaction.getAmount());
        assertEquals(origin, transaction.getOriginAccount());
        assertEquals(destination, transaction.getDestinationAccount());
        assertNotNull(transaction.getCreatedAt());
    }

    private void assertAccountResponse(AccountResponse response, Account account) {
        assertEquals(account.getId(), response.getId());
        assertEquals(account.getCustomer().getId(), response.getCustomerId());
        assertEquals(account.getAccountNumber(), response.getAccountNumber());
        assertEquals(account.getAgency(), response.getAgency());
        assertEquals(account.getBalance(), response.getBalance());
        assertEquals(account.getStatus(), response.getStatus());
        assertEquals(account.getCreatedAt(), response.getCreatedAt());
    }
}

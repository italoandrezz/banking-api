package com.italo.bankingapi.service;

import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.account.*;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.InvalidAmountException;
import com.italo.bankingapi.exception.InsufficientBalanceException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class AccountService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999.99");

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final AuthenticatedCustomer authenticatedCustomer;

    public AccountResponse createAccount(CreateAccountRequest request) {
        Customer customer = findCustomerOrThrow(request.getCustomerId());
        authenticatedCustomer.requireOwner(customer.getId());
        String accountNumber = generateUniqueAccountNumber();
        Account account = Account.builder()
                .customer(customer)
                .accountNumber(accountNumber)
                .agency("0001")
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        Account savedAccount = accountRepository.save(account);
        return toAccountResponse(savedAccount);
    }
    public AccountResponse findAccountById(UUID id) {
        Account account = findOwnedAccountOrThrow(id);
        return toAccountResponse(account);
    }
    public List<AccountResponse> findAllAccounts() {
        List<Account> accounts = accountRepository.findByCustomerId(authenticatedCustomer.getCustomer().getId());
        return accounts.stream().map(this::toAccountResponse).toList();
    }
    private String generateUniqueAccountNumber() {
        String accountNumber;
        do {
            accountNumber = String.valueOf(
                    ThreadLocalRandom.current()
                            .nextInt(10_000_000, 100_000_000)
            );
        } while (accountRepository.existsByAccountNumber(accountNumber));
        return accountNumber;
    }
    @Transactional
    public AccountResponse deposit(UUID id, DepositRequest request) {
        validateAmount(request.getAmount());
        Account account = findAccountForUpdateOrThrow(id);
        authenticatedCustomer.requireOwner(account.getCustomer().getId());
        account.setBalance(creditedBalance(account, request.getAmount()));
        Account updatedAccount = accountRepository.save(account);
        saveTransaction(
                updatedAccount,
                null,
                TransactionType.DEPOSIT,
                request.getAmount(),
                "Account deposit");
        return toAccountResponse(updatedAccount);
    }
    @Transactional
    public AccountResponse withdraw(UUID id, WithdrawRequest request) {
        validateAmount(request.getAmount());
        Account account = findAccountForUpdateOrThrow(id);
        authenticatedCustomer.requireOwner(account.getCustomer().getId());
        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        account.setBalance(
                account.getBalance().subtract(request.getAmount())
        );
        Account updatedAccount = accountRepository.save(account);
        saveTransaction(
                updatedAccount,
                null,
                TransactionType.WITHDRAW,
                request.getAmount(),
                "Account withdrawal");
        return toAccountResponse(updatedAccount);
    }
    @Transactional
    public AccountResponse transfer(TransferRequest request) {
        validateAmount(request.getAmount());
        UUID sourceId = request.getSourceAccountId();
        UUID destinationId = request.getDestinationAccountId();
        // Preserve source existence/ownership checks before accessing the destination.
        // Read only the owner ID so no stale Account balance enters the persistence context.
        UUID ownerId = accountRepository.findCustomerIdByAccountId(sourceId)
                .orElseThrow(() -> new NotFoundException("Account not found."));
        authenticatedCustomer.requireOwner(ownerId);
        // Both A -> B and B -> A acquire locks in the same UUID order.
        // This avoids a cycle where each transfer holds the other's next lock.
        boolean sourceFirst = sourceId.compareTo(destinationId) <= 0;
        Account first = findAccountForUpdateOrThrow(sourceFirst ? sourceId : destinationId);
        Account second = sourceId.equals(destinationId) ? first
                : findAccountForUpdateOrThrow(sourceFirst ? destinationId : sourceId);
        Account sourceAccount = sourceFirst ? first : second;
        Account destinationAccount = sourceFirst ? second : first;
        authenticatedCustomer.requireOwner(sourceAccount.getCustomer().getId());
        if (sourceAccount.getId().equals(destinationAccount.getId())) {
            throw new ConflictException("Source and destination accounts must be different.");
        }
        if (sourceAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        BigDecimal destinationBalance = creditedBalance(destinationAccount, request.getAmount());
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(request.getAmount()));
        destinationAccount.setBalance(destinationBalance);
        accountRepository.save(sourceAccount);
        accountRepository.save(destinationAccount);
        saveTransaction(
                sourceAccount,
                destinationAccount,
                TransactionType.TRANSFER,
                request.getAmount(),
                "Account transfer");
        return toAccountResponse(sourceAccount);
    }
    private void validateAmount(BigDecimal amount) {
        // Match NUMERIC(15,2) without rounding, including direct service calls.
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(MAX_BALANCE) > 0) {
            throw new InvalidAmountException(
                    "Amount must be positive, have at most 2 decimal places and not exceed 9999999999999.99.");
        }
    }

    private BigDecimal creditedBalance(Account account, BigDecimal amount) {
        BigDecimal balance = account.getBalance().add(amount);
        if (balance.compareTo(MAX_BALANCE) > 0) {
            throw new ConflictException("Resulting balance exceeds the maximum of 9999999999999.99.");
        }
        return balance;
    }

    private void saveTransaction(
            Account originAccount,
            Account destinationAccount,
            TransactionType type,
            BigDecimal amount,
            String description) {

        Transaction transaction = Transaction.builder()
                .originAccount(originAccount)
                .destinationAccount(destinationAccount)
                .type(type)
                .amount(amount)
                .description(description)
                .createdAt(LocalDateTime.now())
                .build();

        transactionRepository.save(transaction);
    }
    private Customer findCustomerOrThrow(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException("Customer not found."));
    }
    private Account findAccountOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException("Account not found."));
    }
    private Account findAccountForUpdateOrThrow(UUID id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account not found."));
    }
    private Account findOwnedAccountOrThrow(UUID id) {
        Account account = findAccountOrThrow(id);
        authenticatedCustomer.requireOwner(account.getCustomer().getId());
        return account;
    }
    private AccountResponse toAccountResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .customerId(account.getCustomer().getId())
                .accountNumber(account.getAccountNumber())
                .agency(account.getAgency())
                .balance(account.getBalance())
                .status(account.getStatus())
                .createdAt(account.getCreatedAt())
                .build();
    }
}

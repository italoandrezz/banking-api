package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.*;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.InsufficientBalanceException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
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

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public AccountResponse createAccount(CreateAccountRequest request) {
        Customer customer = findCustomerOrThrow(request.getCustomerId());
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
        Account account = findAccountOrThrow(id);
        return toAccountResponse(account);
    }
    public List<AccountResponse> findAllAccounts() {
        List<Account> accounts = accountRepository.findAll();
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
    public AccountResponse deposit(UUID id, DepositRequest request) {
        Account account = findAccountOrThrow(id);
        account.setBalance(account.getBalance().add(request.getAmount()));
        Account updatedAccount = accountRepository.save(account);
        return toAccountResponse(updatedAccount);
    }
    public AccountResponse withdraw(UUID id, WithdrawRequest request) {
        Account account = findAccountOrThrow(id);
        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        account.setBalance(
                account.getBalance().subtract(request.getAmount())
        );
        Account updatedAccount = accountRepository.save(account);
        return toAccountResponse(updatedAccount);
    }
    @Transactional
    public AccountResponse transfer(TransferRequest request) {
        Account sourceAccount = findAccountOrThrow(request.getSourceAccountId());
        Account destinationAccount = findAccountOrThrow(request.getDestinationAccountId());
        if (sourceAccount.getId().equals(destinationAccount.getId())) {
            throw new ConflictException("Source and destination accounts must be different.");
        }
        if (sourceAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(request.getAmount()));
        destinationAccount.setBalance(destinationAccount.getBalance().add(request.getAmount()));
        accountRepository.save(sourceAccount);
        accountRepository.save(destinationAccount);
        return toAccountResponse(sourceAccount);
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
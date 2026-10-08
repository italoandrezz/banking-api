package com.italo.bankingapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.account.*;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.*;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotentFinancialService {
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final IdempotencyRepository idempotency;
    private final AuthenticatedCustomer authenticatedCustomer;
    private final ObjectMapper mapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountResponse execute(String key, TransactionType operation, UUID source, UUID destination, BigDecimal amount) {
        if (key == null) return perform(operation, source, destination, amount);
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores, colons or hyphens.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new InvalidAmountException("Invalid financial amount.");
        }
        UUID customerId = authenticatedCustomer.getCustomer().getId();
        authenticatedCustomer.requireOwner(accountRepository.findCustomerIdByAccountId(source)
                .orElseThrow(() -> new NotFoundException("Account not found.")));
        String fingerprint = operation + "|" + source + "|" + destination + "|" + amount.stripTrailingZeros().toPlainString();
        if (!idempotency.claim(customerId, key, fingerprint)) {
            var stored = idempotency.find(customerId, key);
            if (!fingerprint.equals(stored.fingerprint())) {
                throw new ConflictException("Idempotency-Key was already used for a different request.");
            }
            try {
                return mapper.readValue(stored.response(), AccountResponse.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot read stored financial response.", e);
            }
        }
        AccountResponse response = perform(operation, source, destination, amount);
        try {
            idempotency.complete(customerId, key, mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store financial response.", e);
        }
        // JDBC and JPA share this transaction: balance, history and replay commit together.
        return response;
    }

    private AccountResponse perform(TransactionType operation, UUID source, UUID destination, BigDecimal amount) {
        return switch (operation) {
            case DEPOSIT -> accounts.deposit(source, new DepositRequest(amount));
            case WITHDRAW -> accounts.withdraw(source, new WithdrawRequest(amount));
            case TRANSFER -> accounts.transfer(new TransferRequest(source, destination, amount));
        };
    }
}

package com.italo.bankingapi.service;

import com.italo.bankingapi.entity.*;
import com.italo.bankingapi.enums.*;
import com.italo.bankingapi.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionStatementIntegrationTest {
    @Autowired TransactionService service;
    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired CustomerRepository customers;
    Account own;
    Account other;
    final LocalDate day = LocalDate.of(2026, 10, 2);

    @BeforeEach
    void setUp() {
        Customer owner = customer("33333333333");
        own = account(owner, "33333333");
        other = account(customer("44444444444"), "44444444");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of()));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void includesIncomingAndOutgoingButExcludesUnrelatedTransactions() {
        var deposit = transaction(own, null, TransactionType.DEPOSIT, day.atStartOfDay());
        var incoming = transaction(other, own, TransactionType.TRANSFER, day.atTime(12, 0));
        var outgoing = transaction(own, other, TransactionType.TRANSFER, day.atTime(13, 0));
        transaction(other, null, TransactionType.DEPOSIT, day.atTime(14, 0));
        var result = service.findTransactionsByAccountId(own.getId(), 0, 20, null, null, null);
        assertEquals(List.of(outgoing.getId(), incoming.getId(), deposit.getId()),
                result.content().stream().map(t -> t.getId()).toList());
        assertEquals(3, result.totalElements());
        assertEquals(1, result.totalPages());
    }

    @Test
    void combinesInclusiveDatesAndTypeForBothTransferDirections() {
        transaction(own, other, TransactionType.TRANSFER, day.minusDays(1).atTime(23, 59));
        var first = transaction(own, other, TransactionType.TRANSFER, day.atStartOfDay());
        var last = transaction(other, own, TransactionType.TRANSFER, day.atTime(23, 59, 59, 999999000));
        transaction(other, own, TransactionType.TRANSFER, day.plusDays(1).atStartOfDay());
        transaction(own, null, TransactionType.DEPOSIT, day.atTime(12, 0));
        transaction(other, null, TransactionType.TRANSFER, day.atTime(12, 0));
        var result = service.findTransactionsByAccountId(own.getId(), 0, 20, day, day, TransactionType.TRANSFER);
        assertEquals(List.of(last.getId(), first.getId()), result.content().stream().map(t -> t.getId()).toList());
        assertEquals(2, result.totalElements());
    }

    @Test
    void supportsIndependentBoundsAndType() {
        transaction(own, null, TransactionType.DEPOSIT, day.minusDays(1).atStartOfDay());
        transaction(own, null, TransactionType.WITHDRAW, day.atStartOfDay());
        transaction(own, null, TransactionType.DEPOSIT, day.plusDays(1).atStartOfDay());
        assertEquals(2, service.findTransactionsByAccountId(own.getId(), 0, 20, day, null, null).totalElements());
        assertEquals(2, service.findTransactionsByAccountId(own.getId(), 0, 20, null, day, null).totalElements());
        assertEquals(1, service.findTransactionsByAccountId(own.getId(), 0, 20, null, null, TransactionType.WITHDRAW).totalElements());
    }

    @Test
    void breaksTimestampTiesAndPreservesTotalsBeyondLastPage() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(transaction(own, null, TransactionType.DEPOSIT, day.atStartOfDay()).getId());
        ids.sort(Comparator.comparing(UUID::toString).reversed());
        List<UUID> actual = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var result = service.findTransactionsByAccountId(own.getId(), page, 2, null, null, null);
            assertEquals(5, result.totalElements());
            assertEquals(3, result.totalPages());
            actual.addAll(result.content().stream().map(t -> t.getId()).toList());
        }
        assertEquals(ids, actual);
        var beyond = service.findTransactionsByAccountId(own.getId(), 3, 2, null, null, null);
        assertTrue(beyond.content().isEmpty());
        assertEquals(5, beyond.totalElements());
    }

    @Test
    void returnsEmptyPageAndAllowsClosedAccountHistory() {
        own.setStatus(AccountStatus.CLOSED);
        accounts.saveAndFlush(own);
        var result = service.findTransactionsByAccountId(own.getId(), 0, 20, null, null, null);
        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
    }

    private Customer customer(String cpf) {
        return customers.saveAndFlush(Customer.builder().fullName("Statement test").cpf(cpf)
                .email(cpf + "@statement.test").password("test-only").birthDate(day.minusYears(30))
                .createdAt(day.atStartOfDay()).build());
    }
    private Account account(Customer owner, String number) {
        return accounts.saveAndFlush(Account.builder().customer(owner).accountNumber(number).agency("0001")
                .balance(BigDecimal.ZERO).status(AccountStatus.ACTIVE).createdAt(day.atStartOfDay()).build());
    }
    private Transaction transaction(Account source, Account destination, TransactionType type, LocalDateTime at) {
        return transactions.saveAndFlush(Transaction.builder().originAccount(source).destinationAccount(destination)
                .type(type).amount(BigDecimal.ONE).description("Statement fixture").createdAt(at).build());
    }
}

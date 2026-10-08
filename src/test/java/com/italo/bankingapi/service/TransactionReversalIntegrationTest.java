package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.*;
import com.italo.bankingapi.dto.transaction.ReversalRequest;
import com.italo.bankingapi.dto.transaction.ReversalResponse;
import com.italo.bankingapi.entity.*;
import com.italo.bankingapi.enums.*;
import com.italo.bankingapi.exception.*;
import com.italo.bankingapi.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionReversalIntegrationTest {
    @Autowired TransactionReversalService reversals;
    @Autowired AccountService accountService;
    @Autowired TransactionService statements;
    @Autowired CustomerRepository customers;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    Customer owner;
    Customer recipient;
    Customer admin;
    Account source;
    Account destination;
    final BigDecimal amount = new BigDecimal("10.00");

    @BeforeEach
    void setUp() {
        assertEquals("banking_api_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        clean();
        owner = customer("11111111111", CustomerRole.CUSTOMER);
        recipient = customer("22222222222", CustomerRole.CUSTOMER);
        admin = customer("33333333333", CustomerRole.ADMIN);
        source = account(owner, "11111111");
        destination = account(recipient, "22222222");
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); clean(); }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void reversesEntireAmountPreservesOriginalAndLinksBothStatementEntries(TransactionType type) {
        UUID id = original(type);
        var before = jdbc.queryForMap("SELECT * FROM transactions WHERE id = ?", id);
        ReversalResponse response = reverse(id);
        assertEquals(id, response.originalTransactionId());
        assertEquals(admin.getId(), response.adminId());
        assertEquals("Correction approved", response.reason());
        assertEquals(amount, response.amount());
        assertNotNull(response.createdAt());
        assertEquals(type == TransactionType.DEPOSIT ? TransactionType.WITHDRAW
                : type == TransactionType.WITHDRAW ? TransactionType.DEPOSIT : TransactionType.TRANSFER, response.type());
        assertEquals(type == TransactionType.TRANSFER ? destination.getId() : source.getId(), response.originAccountId());
        assertEquals(type == TransactionType.TRANSFER ? source.getId() : null, response.destinationAccountId());
        balance(source, "100.00"); balance(destination, "100.00");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM transactions WHERE id = ?", id));
        assertEquals(2, count());
        authenticate(owner);
        var history = statements.findTransactionsByAccountId(source.getId(), 0, 20, null, null, null).content();
        assertEquals(2, history.size());
        var originalEntry = history.stream().filter(t -> t.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(response.id(), originalEntry.getReversalTransactionId());
        var inverseEntry = history.stream().filter(t -> t.getId().equals(response.id())).findFirst().orElseThrow();
        assertEquals(id, inverseEntry.getOriginalTransactionId());
        if (type == TransactionType.TRANSFER) {
            authenticate(recipient);
            assertEquals(2, statements.findTransactionsByAccountId(destination.getId(), 0, 20, null, null, null).totalElements());
        }
    }

    @Test
    void duplicateAndReversalOfReversalAreRejected() {
        var id = original(TransactionType.DEPOSIT);
        var response = reverse(id);
        assertThrows(ConflictException.class, () -> reverse(id));
        assertThrows(ConflictException.class, () -> reverse(response.id()));
        assertEquals(2, count()); balance(source, "100.00");
    }

    @Test
    void customerCannotCallAdministrativeServiceDirectly() {
        var id = original(TransactionType.DEPOSIT);
        authenticate(owner);
        assertThrows(AccessDeniedException.class, () -> reversals.reverse(id, new ReversalRequest("Unauthorized")));
        assertEquals(1, count()); balance(source, "110.00");
    }

    @Test
    void missingTransactionAndInvalidReasonsAreRejected() {
        authenticate(admin);
        assertThrows(NotFoundException.class, () -> reverse(UUID.randomUUID()));
        for (String reason : Arrays.asList(null, "", "  ", "a".repeat(256))) {
            assertThrows(InvalidReversalException.class, () -> reversals.reverse(UUID.randomUUID(), new ReversalRequest(reason)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"origin:BLOCKED", "origin:CLOSED", "destination:BLOCKED", "destination:CLOSED"})
    void requiresBothAccountsToBeActive(String input) {
        var id = original(TransactionType.TRANSFER);
        String[] parts = input.split(":");
        UUID accountId = parts[0].equals("origin") ? source.getId() : destination.getId();
        jdbc.update("UPDATE accounts SET status = ?::account_status WHERE id = ?", parts[1], accountId);
        assertThrows(ConflictException.class, () -> reverse(id));
        assertEquals(1, count()); balance(source, "90.00"); balance(destination, "110.00");
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"DEPOSIT", "TRANSFER"})
    void insufficientDebitBalanceRejectsWithoutChangingOtherAccount(TransactionType type) {
        var id = original(type);
        Account debited = type == TransactionType.TRANSFER ? destination : source;
        jdbc.update("UPDATE accounts SET balance = 0 WHERE id = ?", debited.getId());
        assertThrows(ConflictException.class, () -> reverse(id));
        balance(debited, "0.00");
        if (type == TransactionType.TRANSFER) balance(source, "90.00");
        assertEquals(1, count());
        jdbc.update("UPDATE accounts SET balance = 10 WHERE id = ?", debited.getId());
        reverse(id); // A failed attempt must not reserve the original transaction.
        assertEquals(2, count());
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"WITHDRAW", "TRANSFER"})
    void creditOverflowRejectsWithoutDebiting(TransactionType type) {
        var id = original(type);
        jdbc.update("UPDATE accounts SET balance = 9999999999999.99 WHERE id = ?", source.getId());
        assertThrows(ConflictException.class, () -> reverse(id));
        balance(source, "9999999999999.99");
        balance(destination, type == TransactionType.TRANSFER ? "110.00" : "100.00");
        assertEquals(1, count());
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void concurrentReversalsHaveExactlyOneWinner(TransactionType type) throws Exception {
        var id = original(type);
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                try { reverse(id); return true; }
                catch (ConflictException e) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(2, count()); balance(source, "100.00"); balance(destination, "100.00");
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void concurrentWithdrawalAndReversalCannotSpendSameFunds() throws Exception {
        var id = original(TransactionType.DEPOSIT);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> reversal = pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { reverse(id); return true; } catch (ConflictException e) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            });
            Future<Boolean> withdrawal = pool.submit(() -> {
                authenticate(owner); assertTrue(start.await(10, TimeUnit.SECONDS));
                try { accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("105.00"))); return true; }
                catch (InsufficientBalanceException e) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            });
            start.countDown();
            boolean reversed = reversal.get(20, TimeUnit.SECONDS);
            boolean withdrawn = withdrawal.get(20, TimeUnit.SECONDS);
            assertNotEquals(reversed, withdrawn);
            balance(source, reversed ? "100.00" : "5.00");
            assertEquals(2, count());
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void failureAtCommitRollsBackBalancesAndAuditAndAllowsRetry() {
        var id = original(TransactionType.TRANSFER);
        jdbc.execute("""
                CREATE FUNCTION banking_api_tests.reject_reversal_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Simulated reversal commit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_reversal_commit AFTER INSERT ON transactions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.original_transaction_id IS NOT NULL)
                EXECUTE FUNCTION banking_api_tests.reject_reversal_commit()
                """);
        assertThrows(RuntimeException.class, () -> reverse(id));
        balance(source, "90.00"); balance(destination, "110.00"); assertEquals(1, count());
        dropTrigger();
        reverse(id);
        balance(source, "100.00"); balance(destination, "100.00"); assertEquals(2, count());
    }

    private UUID original(TransactionType type) {
        authenticate(owner);
        switch (type) {
            case DEPOSIT -> accountService.deposit(source.getId(), new DepositRequest(amount));
            case WITHDRAW -> accountService.withdraw(source.getId(), new WithdrawRequest(amount));
            case TRANSFER -> accountService.transfer(new TransferRequest(source.getId(), destination.getId(), amount));
        }
        return jdbc.queryForObject("SELECT id FROM transactions", UUID.class);
    }
    private ReversalResponse reverse(UUID id) {
        authenticate(admin);
        return reversals.reverse(id, new ReversalRequest("  Correction approved  "));
    }
    private void authenticate(Customer user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
    private Customer customer(String cpf, CustomerRole role) {
        return customers.saveAndFlush(Customer.builder().fullName("Reversal test").cpf(cpf).email(cpf + "@reversal.test")
                .password("test-only").birthDate(LocalDate.of(2000, 1, 1)).createdAt(LocalDateTime.now()).role(role).build());
    }
    private Account account(Customer owner, String number) {
        return accounts.saveAndFlush(Account.builder().customer(owner).accountNumber(number).agency("0001")
                .balance(new BigDecimal("100.00")).status(AccountStatus.ACTIVE).createdAt(LocalDateTime.now()).build());
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class); }
    private void balance(Account account, String expected) {
        assertEquals(new BigDecimal(expected), jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account.getId()));
    }
    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reversal_commit ON banking_api_tests.transactions");
        jdbc.execute("DROP FUNCTION IF EXISTS banking_api_tests.reject_reversal_commit()");
    }
    private void clean() {
        dropTrigger();
        jdbc.execute("TRUNCATE TABLE banking_api_tests.financial_idempotency, banking_api_tests.transactions, banking_api_tests.accounts, banking_api_tests.addresses, banking_api_tests.customers");
    }
}

package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.DepositRequest;
import com.italo.bankingapi.dto.account.TransferRequest;
import com.italo.bankingapi.dto.account.WithdrawRequest;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.exception.InsufficientBalanceException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Real commits, separate connections and a SecurityContext per worker; no test-managed transaction. */
@SpringBootTest(properties = "spring.datasource.hikari.connection-init-sql="
        + "SET search_path TO banking_api_tests; SET lock_timeout TO '5s'; SET statement_timeout TO '8s'")
@ActiveProfiles("test")
@Timeout(30)
class AccountConcurrencyIntegrationTest {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Customer owner;
    private Customer recipient;
    private Account a;
    private Account b;

    @BeforeEach
    void setUp() {
        assertEquals("banking_api_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        cleanFixtures();
        owner = customer("11111111111", "concurrent-owner@test.com");
        recipient = customer("22222222222", "concurrent-recipient@test.com");
        a = account(owner, "11111111", "100.00");
        b = account(recipient, "22222222", "50.00");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanFixtures();
    }

    @Test
    void shouldAllowOnlyOneWithdrawalWhenBothTryToSpendTheSameBalance() throws Exception {
        List<Boolean> results = concurrently(owner, this::withdrawEighty, owner, this::withdrawEighty);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertBalances("20.00", "50.00");
        assertHistory("WITHDRAW", List.of(new BigDecimal("80.00")));
    }

    @Test
    void shouldKeepBothConcurrentDeposits() throws Exception {
        concurrently(owner, () -> accountService.deposit(a.getId(), new DepositRequest(new BigDecimal("50.00"))),
                owner, () -> accountService.deposit(a.getId(), new DepositRequest(new BigDecimal("30.00"))));
        assertBalances("180.00", "50.00");
        assertHistory("DEPOSIT", List.of(new BigDecimal("30.00"), new BigDecimal("50.00")));
    }

    @Test
    void shouldKeepBothConcurrentTransfersAndConserveTotalBalance() throws Exception {
        concurrently(owner, () -> transfer(a, b, "30.00"), owner, () -> transfer(a, b, "20.00"));
        assertBalances("50.00", "100.00");
        assertHistory("TRANSFER", List.of(new BigDecimal("20.00"), new BigDecimal("30.00")));
        assertTransfer(a, b, "30.00");
        assertTransfer(a, b, "20.00");
        assertTotalBalance();
    }

    @Test
    void shouldCompleteCrossedTransfersWithoutDeadlockOrLostUpdates() throws Exception {
        concurrently(owner, () -> transfer(a, b, "30.00"), recipient, () -> transfer(b, a, "20.00"));
        assertBalances("90.00", "60.00");
        assertHistory("TRANSFER", List.of(new BigDecimal("20.00"), new BigDecimal("30.00")));
        assertTransfer(a, b, "30.00");
        assertTransfer(b, a, "20.00");
        assertTotalBalance();
    }

    private boolean withdrawEighty() {
        try {
            accountService.withdraw(a.getId(), new WithdrawRequest(new BigDecimal("80.00")));
            return true;
        } catch (InsufficientBalanceException expected) {
            return false;
        }
    }

    private Object transfer(Account source, Account destination, String amount) {
        return accountService.transfer(new TransferRequest(source.getId(), destination.getId(), new BigDecimal(amount)));
    }

    private <T> List<T> concurrently(Customer firstOwner, Callable<T> first,
                                    Customer secondOwner, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // Hold both rows until PostgreSQL reports both workers waiting on locks.
            // A start latch alone could accidentally let the operations run sequentially.
            List<Future<T>> futures = new TransactionTemplate(transactionManager).execute(status -> {
                int holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                for (UUID id : List.of(a.getId(), b.getId()).stream().sorted().toList()) {
                    jdbc.queryForObject("SELECT id FROM banking_api_tests.accounts WHERE id = ? FOR UPDATE", UUID.class, id);
                }
                List<Future<T>> tasks = List.of(
                        executor.submit(() -> asCustomer(firstOwner, first, ready, start)),
                        executor.submit(() -> asCustomer(secondOwner, second, ready, start)));
                await(ready);
                start.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("""
                            WITH RECURSIVE blocked(pid) AS (
                                SELECT CAST(? AS integer)
                                UNION
                                SELECT activity.pid FROM pg_stat_activity activity
                                JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                                WHERE activity.datname = current_database()
                            )
                            SELECT count(*) - 1 FROM blocked
                            """, Integer.class, holderPid);
                    if (waiting >= 2) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                } while (System.nanoTime() < deadline);
                assertEquals(2, waiting, "Both operations must contend on real PostgreSQL locks");
                return tasks;
            }); // Commit releases the fixture locks; workers must now serialize correctly.
            assertNotNull(futures);
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Workers must finish before fixtures are removed");
        }
    }

    private <T> T asCustomer(Customer customer, Callable<T> operation,
                             CountDownLatch ready, CountDownLatch start) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(customer, null, List.of()));
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
            // Each service call owns its transaction; the test datasource bounds database waits.
            return operation.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Workers did not become ready");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private void assertBalances(String expectedA, String expectedB) {
        assertEquals(new BigDecimal(expectedA), balance(a));
        assertEquals(new BigDecimal(expectedB), balance(b));
    }

    private BigDecimal balance(Account account) {
        return jdbc.queryForObject("SELECT balance FROM banking_api_tests.accounts WHERE id = ?", BigDecimal.class, account.getId());
    }

    private void assertHistory(String type, List<BigDecimal> amounts) {
        assertEquals(amounts.size(), jdbc.queryForObject("SELECT count(*) FROM banking_api_tests.transactions", Integer.class));
        assertEquals(amounts, jdbc.queryForList(
                "SELECT amount FROM banking_api_tests.transactions WHERE type::text = ? ORDER BY amount", BigDecimal.class, type));
        if (!type.equals("TRANSFER")) {
            assertEquals(amounts.size(), jdbc.queryForObject("""
                    SELECT count(*) FROM banking_api_tests.transactions
                    WHERE account_id = ? AND destination_account_id IS NULL
                    """, Integer.class, a.getId()));
        }
    }

    private void assertTransfer(Account source, Account destination, String amount) {
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM banking_api_tests.transactions
                WHERE account_id = ? AND destination_account_id = ? AND amount = ?
                """, Integer.class, source.getId(), destination.getId(), new BigDecimal(amount)));
    }

    private void assertTotalBalance() {
        assertEquals(new BigDecimal("150.00"), balance(a).add(balance(b)));
    }

    private Customer customer(String cpf, String email) {
        return customerRepository.saveAndFlush(Customer.builder().fullName("Concurrency Test")
                .cpf(cpf).email(email).password("test-only").birthDate(LocalDate.of(2000, 1, 1))
                .createdAt(LocalDateTime.now()).build());
    }

    private Account account(Customer customer, String number, String balance) {
        return accountRepository.saveAndFlush(Account.builder().customer(customer).accountNumber(number)
                .agency("0001").balance(new BigDecimal(balance)).status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).build());
    }

    private void cleanFixtures() {
        jdbc.execute("""
                TRUNCATE TABLE banking_api_tests.transactions, banking_api_tests.accounts,
                banking_api_tests.addresses, banking_api_tests.customers
                """);
    }
}

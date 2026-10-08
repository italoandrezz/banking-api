package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.AccountResponse;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.InsufficientBalanceException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialIdempotencyIntegrationTest {
    @Autowired IdempotentFinancialService service;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    Customer owner;
    Customer recipient;
    Account source;
    Account destination;

    @BeforeEach
    void setUp() {
        assertEquals("banking_api_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        clean();
        owner = customer("55555555555");
        recipient = customer("66666666666");
        source = account(owner, "55555555");
        destination = account(recipient, "66666666");
        authenticate(owner);
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); clean(); }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void replaysOriginalSnapshotAfterLaterAccountChanges(TransactionType type) {
        var first = execute("retry", type, "10.00");
        service.execute(null, TransactionType.DEPOSIT, source.getId(), null, new BigDecimal("5.00"));
        accountService.block(source.getId());
        var replay = execute("retry", type, "10.0");
        assertEquals(first.getBalance(), replay.getBalance());
        assertEquals(AccountStatus.ACTIVE, replay.getStatus());
        assertEquals(first.getCreatedAt(), replay.getCreatedAt());
        assertEquals(2, count("transactions"));
        assertEquals(1, count("financial_idempotency"));
        assertBalance(source, type == TransactionType.DEPOSIT ? "115.00" : "95.00");
        assertBalance(destination, type == TransactionType.TRANSFER ? "110.00" : "100.00");
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void simultaneousRetriesMoveMoneyOnce(TransactionType type) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AccountResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                authenticate(owner);
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return execute("concurrent", type, "10.00");
                } finally { SecurityContextHolder.clearContext(); }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) assertEquals(new BigDecimal(type == TransactionType.DEPOSIT ? "110.00" : "90.00"),
                    future.get(20, TimeUnit.SECONDS).getBalance());
            assertEquals(1, count("transactions"));
            assertEquals(1, count("financial_idempotency"));
            assertBalance(source, type == TransactionType.DEPOSIT ? "110.00" : "90.00");
            assertBalance(destination, type == TransactionType.TRANSFER ? "110.00" : "100.00");
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void rejectsDifferentAmountOperationSourceAndDestination() {
        execute("same", TransactionType.TRANSFER, "10.00");
        assertThrows(ConflictException.class, () -> execute("same", TransactionType.TRANSFER, "20.00"));
        assertThrows(ConflictException.class, () -> execute("same", TransactionType.DEPOSIT, "10.00"));
        var second = account(owner, "77777777");
        assertThrows(ConflictException.class, () -> service.execute("same", TransactionType.TRANSFER,
                second.getId(), destination.getId(), new BigDecimal("10.00")));
        assertThrows(ConflictException.class, () -> service.execute("same", TransactionType.TRANSFER,
                source.getId(), second.getId(), new BigDecimal("10.00")));
        assertEquals(1, count("transactions"));
    }

    @Test
    void failedOperationCanBeRetriedWithSameKey() {
        assertThrows(InsufficientBalanceException.class, () -> execute("failed", TransactionType.WITHDRAW, "110.00"));
        assertEquals(0, count("financial_idempotency"));
        assertEquals(0, count("transactions"));
        execute("fund", TransactionType.DEPOSIT, "20.00");
        execute("failed", TransactionType.WITHDRAW, "110.00");
        assertBalance(source, "10.00");
    }

    @Test
    void commitFailureRollsBackBalanceHistoryAndKey() {
        jdbc.execute("""
                CREATE FUNCTION banking_api_tests.reject_idempotency_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Simulated idempotency commit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_idempotency_commit AFTER INSERT ON financial_idempotency
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                EXECUTE FUNCTION banking_api_tests.reject_idempotency_commit()
                """);
        assertThrows(RuntimeException.class, () -> execute("commit", TransactionType.TRANSFER, "10.00"));
        assertBalance(source, "100.00");
        assertBalance(destination, "100.00");
        assertEquals(0, count("transactions"));
        assertEquals(0, count("financial_idempotency"));
        dropTrigger();
        execute("commit", TransactionType.TRANSFER, "10.00");
        assertEquals(1, count("transactions"));
    }

    @Test
    void keysAreScopedByCustomerAndDifferentKeysExecuteIndependently() {
        execute("shared", TransactionType.DEPOSIT, "10.00");
        execute("different", TransactionType.DEPOSIT, "10.00");
        authenticate(recipient);
        service.execute("shared", TransactionType.DEPOSIT, destination.getId(), null, new BigDecimal("10.00"));
        assertBalance(source, "120.00");
        assertBalance(destination, "110.00");
        assertEquals(3, count("financial_idempotency"));
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void httpReplaysSameResponseAndRejectsChangedPayload(TransactionType type) throws Exception {
        String path = type == TransactionType.TRANSFER ? "/accounts/transfer"
                : "/accounts/" + source.getId() + "/" + (type == TransactionType.DEPOSIT ? "deposit" : "withdraw");
        String body = type == TransactionType.TRANSFER
                ? "{\"sourceAccountId\":\"" + source.getId() + "\",\"destinationAccountId\":\"" + destination.getId() + "\",\"amount\":10.00}"
                : "{\"amount\":10.00}";
        String bearer = "Bearer " + jwt.generateToken(owner.getId());
        var first = mvc.perform(post(path).header("Authorization", bearer).header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post(path).header("Authorization", bearer).header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(content().json(first));
        mvc.perform(post(path).header("Authorization", bearer).header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("10.00", "20.00"))).andExpect(status().isConflict());
        assertEquals(1, count("transactions"));
    }

    private AccountResponse execute(String key, TransactionType type, String amount) {
        return service.execute(key, type, source.getId(), type == TransactionType.TRANSFER ? destination.getId() : null, new BigDecimal(amount));
    }
    private void authenticate(Customer customer) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(customer, null, List.of()));
    }
    private Customer customer(String cpf) {
        return customers.saveAndFlush(Customer.builder().fullName("Idempotency test").cpf(cpf).email(cpf + "@test.com")
                .password("test-only").birthDate(LocalDate.of(2000, 1, 1)).createdAt(LocalDateTime.now()).build());
    }
    private Account account(Customer customer, String number) {
        return accounts.saveAndFlush(Account.builder().customer(customer).accountNumber(number).agency("0001")
                .balance(new BigDecimal("100.00")).status(AccountStatus.ACTIVE).createdAt(LocalDateTime.now()).build());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private void assertBalance(Account account, String balance) {
        assertEquals(new BigDecimal(balance), jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account.getId()));
    }
    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_idempotency_commit ON financial_idempotency");
        jdbc.execute("DROP FUNCTION IF EXISTS banking_api_tests.reject_idempotency_commit()");
    }
    private void clean() {
        dropTrigger();
        jdbc.execute("TRUNCATE TABLE banking_api_tests.financial_idempotency, banking_api_tests.transactions, banking_api_tests.accounts, banking_api_tests.addresses, banking_api_tests.customers");
    }
}

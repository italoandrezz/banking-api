package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.DepositRequest;
import com.italo.bankingapi.dto.account.WithdrawRequest;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intentionally not transactional: the service proxy must commit or roll back
 * before assertions read the persisted state through a separate JDBC call.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("banking_api_tests",
                jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
        cleanFixtures();
        Customer owner = customer("11111111111", "owner@test.com");
        Customer recipient = customer("22222222222", "recipient@test.com");
        source = account(owner, "11111111", "100.00");
        destination = account(recipient, "22222222", "50.00");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanFixtures();
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"DEPOSIT", "WITHDRAW"})
    void shouldCommitBalanceAndHistoryTogether(TransactionType type) {
        moveMoney(type);

        assertBalance(source, type == TransactionType.DEPOSIT ? "125.00" : "75.00");
        assertBalance(destination, "50.00");
        assertEquals(1, historyCount());
        var transaction = jdbcTemplate.queryForMap("""
                SELECT account_id, destination_account_id, type::text AS type, amount, created_at
                FROM banking_api_tests.transactions
                """);
        assertEquals(source.getId(), transaction.get("account_id"));
        assertNull(transaction.get("destination_account_id"));
        assertEquals(type.name(), transaction.get("type"));
        assertEquals(new BigDecimal("25.00"), transaction.get("amount"));
        assertNotNull(transaction.get("created_at"));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"DEPOSIT", "WITHDRAW"})
    void shouldRollBackBalanceWhenHistoryCannotCommit(TransactionType type) {
        rejectHistoryAtCommit();

        RuntimeException exception = assertThrows(RuntimeException.class, () -> moveMoney(type));
        assertTrue(rootCause(exception).getMessage().contains("Simulated history write failure"));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    private void moveMoney(TransactionType type) {
        if (type == TransactionType.DEPOSIT) {
            accountService.deposit(source.getId(), new DepositRequest(new BigDecimal("25.00")));
        } else {
            accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("25.00")));
        }
    }

    private void rejectHistoryAtCommit() {
        // PostgreSQL rejects the transaction at commit, after Hibernate has
        // flushed both the account update and the history insert.
        jdbcTemplate.execute("""
                CREATE FUNCTION banking_api_tests.reject_history_write() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated history write failure' USING ERRCODE = '23514';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER reject_history_write
                AFTER INSERT ON banking_api_tests.transactions
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION banking_api_tests.reject_history_write()
                """);
    }

    private void assertBalance(Account account, String expected) {
        BigDecimal actual = jdbcTemplate.queryForObject(
                "SELECT balance FROM banking_api_tests.accounts WHERE id = ?",
                BigDecimal.class, account.getId());
        assertEquals(new BigDecimal(expected), actual);
    }

    private int historyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking_api_tests.transactions", Integer.class);
    }

    private Throwable rootCause(Throwable exception) {
        while (exception.getCause() != null) {
            exception = exception.getCause();
        }
        return exception;
    }

    private Customer customer(String cpf, String email) {
        return customerRepository.saveAndFlush(Customer.builder()
                .fullName("Integration Test").cpf(cpf).email(email).password("test-only")
                .birthDate(LocalDate.of(2000, 1, 1)).createdAt(LocalDateTime.now()).build());
    }

    private Account account(Customer customer, String number, String balance) {
        return accountRepository.saveAndFlush(Account.builder()
                .customer(customer).accountNumber(number).agency("0001")
                .balance(new BigDecimal(balance)).status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).build());
    }

    private void cleanFixtures() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_history_write ON banking_api_tests.transactions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS banking_api_tests.reject_history_write()");
        jdbcTemplate.execute("""
                TRUNCATE TABLE banking_api_tests.transactions, banking_api_tests.accounts,
                banking_api_tests.addresses, banking_api_tests.customers
                """);
    }
}

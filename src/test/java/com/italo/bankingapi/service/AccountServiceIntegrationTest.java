package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.account.DepositRequest;
import com.italo.bankingapi.dto.account.TransferRequest;
import com.italo.bankingapi.dto.account.WithdrawRequest;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import com.italo.bankingapi.exception.InvalidAmountException;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.repository.AccountRepository;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void shouldCommitTransferBetweenDifferentCustomersWithOneHistoryEntry() {
        accountService.transfer(transferRequest());

        assertBalance(source, "75.00");
        assertBalance(destination, "75.00");
        assertEquals(new BigDecimal("150.00"), jdbcTemplate.queryForObject(
                "SELECT sum(balance) FROM banking_api_tests.accounts", BigDecimal.class));
        assertEquals(1, historyCount());
        var transaction = jdbcTemplate.queryForMap("""
                SELECT account_id, destination_account_id, type::text AS type, amount
                FROM banking_api_tests.transactions
                """);
        assertEquals(source.getId(), transaction.get("account_id"));
        assertEquals(destination.getId(), transaction.get("destination_account_id"));
        assertEquals("TRANSFER", transaction.get("type"));
        assertEquals(new BigDecimal("25.00"), transaction.get("amount"));
    }

    @Test
    void shouldRollBackBothBalancesWhenTransferHistoryCannotCommit() {
        rejectHistoryAtCommit();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> accountService.transfer(transferRequest()));
        assertTrue(rootCause(exception).getMessage().contains("Simulated history write failure"));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    @Test
    void shouldRollBackDebitAndHistoryWhenDestinationCreditCannotCommit() {
        rejectDestinationCreditAtCommit();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> accountService.transfer(transferRequest()));
        assertTrue(rootCause(exception).getMessage().contains("Simulated destination credit failure"));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    @Test
    void shouldPreservePreviouslyCommittedTransferWhenNextTransferFails() {
        accountService.transfer(transferRequest());
        var previousHistory = jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions");
        rejectHistoryAtCommit();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> accountService.transfer(transferRequest()));
        assertTrue(rootCause(exception).getMessage().contains("Simulated history write failure"));
        assertBalance(source, "75.00");
        assertBalance(destination, "75.00");
        assertEquals(previousHistory,
                jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions"));
    }

    private TransferRequest transferRequest() {
        return new TransferRequest(source.getId(), destination.getId(), new BigDecimal("25.00"));
    }

    @Test
    void shouldCloseZeroBalanceAccountAndPreserveHistory() {
        accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("100.00")));
        var history = jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions");
        assertEquals(AccountStatus.CLOSED, accountService.close(source.getId()).getStatus());
        var persisted = accountRepository.findById(source.getId()).orElseThrow();
        assertEquals(AccountStatus.CLOSED, persisted.getStatus());
        assertNotNull(persisted.getUpdatedAt());
        assertBalance(source, "0.00");
        assertThrows(ConflictException.class, () -> accountService.close(source.getId()));
        assertThrows(ConflictException.class, () -> accountService.block(source.getId()));
        assertThrows(ConflictException.class, () -> accountService.unblock(source.getId()));
        assertThrows(ConflictException.class,
                () -> accountService.deposit(source.getId(), new DepositRequest(new BigDecimal("1.00"))));
        assertThrows(ConflictException.class,
                () -> accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("1.00"))));
        assertThrows(ConflictException.class, () -> accountService.transfer(transferRequest()));
        assertEquals(AccountStatus.CLOSED, accountService.findAccountById(source.getId()).getStatus());
        assertEquals(history, jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions"));
        assertBalance(source, "0.00");
        assertBalance(destination, "50.00");
    }

    @Test
    void shouldRejectClosingAccountWithMoneyWithoutChangingIt() {
        assertThrows(ConflictException.class, () -> accountService.close(source.getId()));
        Account persisted = accountRepository.findById(source.getId()).orElseThrow();
        assertEquals(AccountStatus.ACTIVE, persisted.getStatus());
        assertNull(persisted.getUpdatedAt());
        assertBalance(source, "100.00");
        assertEquals(0, historyCount());
    }

    @Test
    void shouldRequireUnblockingBeforeClosingZeroBalanceAccount() {
        accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("100.00")));
        accountService.block(source.getId());
        assertThrows(ConflictException.class, () -> accountService.close(source.getId()));
        assertEquals(AccountStatus.BLOCKED, accountRepository.findById(source.getId()).orElseThrow().getStatus());
        accountService.unblock(source.getId());
        accountService.close(source.getId());
        assertEquals(AccountStatus.CLOSED, accountRepository.findById(source.getId()).orElseThrow().getStatus());
        assertBalance(source, "0.00");
        assertEquals(1, historyCount());
    }

    @Test
    void shouldBlockAndUnblockWithoutChangingBalanceOrHistory() {
        accountService.deposit(source.getId(), new DepositRequest(new BigDecimal("1.00")));
        var history = jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions");
        assertEquals(AccountStatus.BLOCKED, accountService.block(source.getId()).getStatus());
        assertEquals("BLOCKED", jdbcTemplate.queryForObject(
                "SELECT status::text FROM banking_api_tests.accounts WHERE id = ?", String.class, source.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM banking_api_tests.accounts WHERE id = ?", LocalDateTime.class, source.getId()));
        assertThrows(ConflictException.class, () -> accountService.block(source.getId()));
        assertBalance(source, "101.00");
        assertEquals(history, jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions"));
        assertEquals(AccountStatus.ACTIVE, accountService.unblock(source.getId()).getStatus());
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status::text FROM banking_api_tests.accounts WHERE id = ?", String.class, source.getId()));
        assertThrows(ConflictException.class, () -> accountService.unblock(source.getId()));
        assertEquals(history, jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions"));
        accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("1.00")));
        assertBalance(source, "100.00");
        assertEquals(2, historyCount());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"BLOCKED", "CLOSED"})
    void shouldRejectEveryOutgoingOperationOnInactiveAccount(AccountStatus status) {
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET status = ?::account_status WHERE id = ?",
                status.name(), source.getId());
        assertThrows(ConflictException.class,
                () -> accountService.deposit(source.getId(), new DepositRequest(new BigDecimal("1.00"))));
        assertThrows(ConflictException.class,
                () -> accountService.withdraw(source.getId(), new WithdrawRequest(new BigDecimal("1.00"))));
        assertThrows(ConflictException.class, () -> accountService.transfer(transferRequest()));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"BLOCKED", "CLOSED"})
    void shouldRejectTransferToInactiveDestinationWithoutDebit(AccountStatus status) {
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET status = ?::account_status WHERE id = ?",
                status.name(), destination.getId());
        assertThrows(ConflictException.class, () -> accountService.transfer(transferRequest()));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    @Test
    void shouldNeverReopenClosedAccount() {
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET status = 'CLOSED' WHERE id = ?", source.getId());
        assertThrows(ConflictException.class, () -> accountService.block(source.getId()));
        assertThrows(ConflictException.class, () -> accountService.unblock(source.getId()));
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status::text FROM banking_api_tests.accounts WHERE id = ?", String.class, source.getId()));
        assertBalance(source, "100.00");
        assertEquals(0, historyCount());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01", "0.001", "0.015", "1.000", "10000000000000", "1E+13"})
    void shouldRejectInvalidAmountsWithoutChangingBalancesOrHistory(String value) {
        BigDecimal amount = value == null ? null : new BigDecimal(value);
        assertThrows(InvalidAmountException.class,
                () -> accountService.deposit(source.getId(), new DepositRequest(amount)));
        assertThrows(InvalidAmountException.class,
                () -> accountService.withdraw(source.getId(), new WithdrawRequest(amount)));
        assertThrows(InvalidAmountException.class,
                () -> accountService.transfer(new TransferRequest(source.getId(), destination.getId(), amount)));
        assertBalance(source, "100.00");
        assertBalance(destination, "50.00");
        assertEquals(0, historyCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1.2", "10.23", "1E+1"})
    void shouldPersistExactAmountsForAllOperations(String value) {
        BigDecimal amount = new BigDecimal(value);
        accountService.deposit(source.getId(), new DepositRequest(amount));
        assertBalance(source, new BigDecimal("100.00").add(amount).toPlainString());
        accountService.withdraw(source.getId(), new WithdrawRequest(amount));
        assertBalance(source, "100.00");
        accountService.transfer(new TransferRequest(source.getId(), destination.getId(), amount));
        assertBalance(source, new BigDecimal("100.00").subtract(amount).toPlainString());
        assertBalance(destination, new BigDecimal("50.00").add(amount).toPlainString());
        assertEquals(3, historyCount());
        assertEquals(List.of(amount.setScale(2), amount.setScale(2), amount.setScale(2)),
                jdbcTemplate.queryForList("SELECT amount FROM banking_api_tests.transactions", BigDecimal.class));
    }

    @Test
    void shouldAcceptMaximumAmountAndRejectDepositOverflow() {
        BigDecimal maximum = new BigDecimal("9999999999999.99");
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET balance = 0 WHERE id = ?", source.getId());
        accountService.deposit(source.getId(), new DepositRequest(maximum));
        assertBalance(source, maximum.toPlainString());
        assertThrows(ConflictException.class,
                () -> accountService.deposit(source.getId(), new DepositRequest(new BigDecimal("0.01"))));
        assertBalance(source, maximum.toPlainString());
        assertEquals(1, historyCount());
        assertEquals(maximum, jdbcTemplate.queryForObject(
                "SELECT amount FROM banking_api_tests.transactions", BigDecimal.class));
        accountService.withdraw(source.getId(), new WithdrawRequest(maximum));
        assertBalance(source, "0.00");
        assertEquals(2, historyCount());
    }

    @Test
    void shouldRejectDestinationOverflowWithoutDebitingSource() {
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET balance = ? WHERE id = ?",
                new BigDecimal("9999999999999.98"), destination.getId());
        accountService.transfer(new TransferRequest(source.getId(), destination.getId(), new BigDecimal("0.01")));
        var previousHistory = jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions");
        assertThrows(ConflictException.class,
                () -> accountService.transfer(new TransferRequest(source.getId(), destination.getId(), new BigDecimal("0.01"))));
        assertBalance(source, "99.99");
        assertBalance(destination, "9999999999999.99");
        assertEquals(previousHistory, jdbcTemplate.queryForList("SELECT * FROM banking_api_tests.transactions"));
    }

    @Test
    void shouldTransferMaximumAmountExactly() {
        BigDecimal maximum = new BigDecimal("9999999999999.99");
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET balance = ? WHERE id = ?", maximum, source.getId());
        jdbcTemplate.update("UPDATE banking_api_tests.accounts SET balance = 0 WHERE id = ?", destination.getId());
        accountService.transfer(new TransferRequest(source.getId(), destination.getId(), maximum));
        assertBalance(source, "0.00");
        assertBalance(destination, maximum.toPlainString());
        assertEquals(1, historyCount());
        assertEquals(maximum, jdbcTemplate.queryForObject(
                "SELECT amount FROM banking_api_tests.transactions", BigDecimal.class));
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

    private void rejectDestinationCreditAtCommit() {
        jdbcTemplate.execute("""
                CREATE FUNCTION banking_api_tests.reject_destination_credit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated destination credit failure' USING ERRCODE = '23514';
                END;
                $$
                """);
        // Only the destination update is rejected, after all pending writes
        // have been flushed. The source debit and history must also roll back.
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER reject_destination_credit
                AFTER UPDATE ON banking_api_tests.accounts
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW WHEN (NEW.id = '%s'::uuid)
                EXECUTE FUNCTION banking_api_tests.reject_destination_credit()
                """.formatted(destination.getId()));
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
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_destination_credit ON banking_api_tests.accounts");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS banking_api_tests.reject_destination_credit()");
        jdbcTemplate.execute("""
                TRUNCATE TABLE banking_api_tests.transactions, banking_api_tests.accounts,
                banking_api_tests.addresses, banking_api_tests.customers
                """);
    }
}

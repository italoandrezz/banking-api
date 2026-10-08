package com.italo.bankingapi.security;

import com.italo.bankingapi.config.SecurityConfig;
import com.italo.bankingapi.config.filter.JwtAuthenticationFilter;
import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.config.security.JwtAuthenticationEntryPoint;
import com.italo.bankingapi.controller.*;
import com.italo.bankingapi.entity.Account;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.entity.Transaction;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.repository.*;
import com.italo.bankingapi.service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CustomerController.class, AccountController.class, TransactionController.class, AuthController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        com.italo.bankingapi.config.security.JsonAccessDeniedHandler.class, AuthenticatedCustomer.class, JwtService.class, AuthService.class, CustomerService.class,
        AccountService.class, TransactionService.class, IdempotentFinancialService.class})
@TestPropertySource(properties = {
        "jwt.secret=01234567890123456789012345678901", "jwt.expiration=86400000"
})
class OwnershipSecurityTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private static final String UPDATE = """
            {"fullName":"Updated Name","email":"updated@test.com","phone":"81999999999","birthDate":"2000-01-01"}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private CustomerRepository customerRepository;
    @MockitoBean private AccountRepository accountRepository;
    @MockitoBean private TransactionRepository transactionRepository;
    @MockitoBean private IdempotencyRepository idempotencyRepository;

    private Customer owner;
    private Customer other;
    private Account ownAccount;
    private Account otherAccount;
    private String bearer;

    @BeforeEach
    void setUp() {
        owner = customer("owner@test.com");
        other = customer("other@test.com");
        ownAccount = account(owner);
        otherAccount = account(other);
        bearer = "Bearer " + jwtService.generateToken(owner.getId());
        when(customerRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(customerRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(accountRepository.findById(ownAccount.getId())).thenReturn(Optional.of(ownAccount));
        when(accountRepository.findById(otherAccount.getId())).thenReturn(Optional.of(otherAccount));
        when(accountRepository.findByIdForUpdate(ownAccount.getId())).thenReturn(Optional.of(ownAccount));
        when(accountRepository.findByIdForUpdate(otherAccount.getId())).thenReturn(Optional.of(otherAccount));
        when(accountRepository.findCustomerIdByAccountId(ownAccount.getId())).thenReturn(Optional.of(owner.getId()));
        when(accountRepository.findCustomerIdByAccountId(otherAccount.getId())).thenReturn(Optional.of(other.getId()));
        when(transactionRepository.findStatement(any(), any(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> Page.empty(invocation.getArgument(4, Pageable.class)));
    }

    @Test
    void shouldReadOwnCustomer() throws Exception {
        mvc.perform(get("/customers/{id}", owner.getId()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad key", "bad/key", "á"})
    void shouldRejectInvalidIdempotencyKey(String key) throws Exception {
        mvc.perform(post("/accounts/{id}/deposit", ownAccount.getId())
                .header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(idempotencyRepository, transactionRepository);
    }

    @Test
    void shouldRejectTooLongIdempotencyKey() throws Exception {
        mvc.perform(post("/accounts/{id}/deposit", ownAccount.getId())
                .header("Authorization", bearer).header("Idempotency-Key", "a".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(idempotencyRepository, transactionRepository);
    }

    @Test
    void shouldCheckOwnershipBeforeLookingUpIdempotencyKey() throws Exception {
        mvc.perform(post("/accounts/{id}/deposit", otherAccount.getId())
                .header("Authorization", bearer).header("Idempotency-Key", "existing-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(idempotencyRepository, transactionRepository);
    }

    @Test
    void shouldUpdateOwnCustomerAndKeepTokenIdentity() throws Exception {
        when(customerRepository.save(any(Customer.class))).thenAnswer(call -> call.getArgument(0));
        mvc.perform(put("/customers/{id}", owner.getId()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("updated@test.com"));
        mvc.perform(get("/customers/{id}", owner.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.getId().toString()));
        verify(customerRepository, never()).findByEmail(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void shouldDenyAccessToAnotherCustomer(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/customers/" + other.getId())
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied."))
                .andExpect(jsonPath("$.trace").doesNotExist());
        verify(customerRepository, never()).save(any());
        verify(customerRepository, never()).delete(any());
    }

    @Test
    void shouldDenyCustomerListing() throws Exception {
        mvc.perform(get("/customers").header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verify(customerRepository, never()).findAll();
    }

    @Test
    void shouldListOnlyOwnedAccounts() throws Exception {
        when(accountRepository.findByCustomerId(owner.getId())).thenReturn(List.of(ownAccount));
        mvc.perform(get("/accounts").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ownAccount.getId().toString()));
        verify(accountRepository).findByCustomerId(owner.getId());
        verify(accountRepository, never()).findAll();
    }

    @Test
    void shouldReadOwnAccount() throws Exception {
        mvc.perform(get("/accounts/{id}", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(owner.getId().toString()));
    }

    @Test
    void shouldDenyReadingAnotherAccount() throws Exception {
        mvc.perform(get("/accounts/{id}", otherAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdraw", "deposit"})
    void shouldDenyMovingMoneyInAnotherAccount(String operation) throws Exception {
        mvc.perform(post("/accounts/{id}/" + operation, otherAccount.getId())
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        assertEquals(new BigDecimal("100.00"), otherAccount.getBalance());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldDenyTransferFromAnotherCustomersAccount() throws Exception {
        mvc.perform(transfer(otherAccount, ownAccount))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        assertEquals(new BigDecimal("100.00"), otherAccount.getBalance());
        assertEquals(new BigDecimal("100.00"), ownAccount.getBalance());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldTransferToAnotherCustomersAccount() throws Exception {
        mvc.perform(transfer(ownAccount, otherAccount))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(90.00));
        assertEquals(new BigDecimal("110.00"), otherAccount.getBalance());
        verify(accountRepository).save(ownAccount);
        verify(accountRepository).save(otherAccount);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void shouldDenyAnotherAccountsHistory() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", otherAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldReadOwnHistory() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647&size=100",
            "page=abc", "size=999999999999", "type=INVALID", "startDate=2026-02-30",
            "endDate=invalid", "startDate=2026-10-02&endDate=2026-10-01"})
    void shouldRejectInvalidStatementParameters(String query) throws Exception {
        mvc.perform(get("/accounts/" + ownAccount.getId() + "/transactions?" + query).header("Authorization", bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldPassStatementFiltersToRepository() throws Exception {
        mvc.perform(get("/accounts/{id}/transactions", ownAccount.getId()).header("Authorization", bearer)
                        .param("page", "1").param("size", "5").param("startDate", "2026-10-01")
                        .param("endDate", "2026-10-02").param("type", "TRANSFER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(5));
        verify(transactionRepository).findStatement(eq(ownAccount.getId()),
                eq(LocalDate.of(2026, 10, 1).atStartOfDay()), eq(LocalDate.of(2026, 10, 3).atStartOfDay()),
                eq(com.italo.bankingapi.enums.TransactionType.TRANSFER),
                eq(org.springframework.data.domain.PageRequest.of(1, 5,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"))));
    }

    @Test
    void shouldDenyCreatingAccountForAnotherCustomer() throws Exception {
        mvc.perform(post("/accounts").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + other.getId() + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).existsByAccountNumber(any());
    }

    @Test
    void shouldCreateAccountForAuthenticatedCustomer() throws Exception {
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> {
            Account account = call.getArgument(0);
            account.setId(UUID.randomUUID());
            return account;
        });
        mvc.perform(post("/accounts").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + owner.getId() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerId").value(owner.getId().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/customers", "/accounts", "/customers/00000000-0000-0000-0000-000000000001",
            "/accounts/00000000-0000-0000-0000-000000000001/transactions"})
    void shouldReturn401WithoutAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.trace").doesNotExist());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", "old-email@test.com", "1-1-1-1-1"})
    void shouldReturn401ForInvalidUuidSubject(String subject) throws Exception {
        mvc.perform(get("/accounts").header("Authorization", "Bearer " + signedToken(subject, SECRET, 60_000)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(accountRepository);
        verify(customerRepository, never()).findById(any());
    }

    @Test
    void shouldReturn401ForNonexistentTokenCustomer() throws Exception {
        mvc.perform(get("/accounts").header("Authorization", "Bearer " + jwtService.generateToken(UUID.randomUUID())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired", "wrong-signature", "missing-expiration"})
    void shouldReturn401ForInvalidToken(String kind) throws Exception {
        String token = switch (kind) {
            case "expired" -> signedToken(owner.getId().toString(), SECRET, -60_000);
            case "wrong-signature" -> signedToken(owner.getId().toString(), "abcdefghijklmnopqrstuvwxyz012345", 60_000);
            case "missing-expiration" -> Jwts.builder().subject(owner.getId().toString())
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
            default -> "not-a-token";
        };
        mvc.perform(get("/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(accountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/customers/", "/accounts/"})
    void shouldReturn404ForNonexistentResource(String path) throws Exception {
        mvc.perform(get(path + UUID.randomUUID()).header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldKeepLoginPublicAndIssueUuidToken() throws Exception {
        owner.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password"));
        when(customerRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@test.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("token").asText();
        assertEquals(owner.getId(), jwtService.extractCustomerId(token));
    }

    @Test
    void shouldKeepRegistrationPublic() throws Exception {
        when(customerRepository.save(any(Customer.class))).thenAnswer(call -> {
            Customer customer = call.getArgument(0);
            customer.setId(UUID.randomUUID());
            return customer;
        });
        mvc.perform(post("/customers").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"New Customer","cpf":"12345678901","email":"new@test.com",
                 "password":"password","phone":"81999999999","birthDate":"2000-01-01"}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.password").doesNotExist());
    }

    private MockHttpServletRequestBuilder transfer(Account source, Account destination) {
        return post("/accounts/transfer").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00}
                        """.formatted(source.getId(), destination.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"block", "unblock"})
    void shouldChangeOwnAccountStatus(String action) throws Exception {
        ownAccount.setStatus(action.equals("block") ? AccountStatus.ACTIVE : AccountStatus.BLOCKED);
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        mvc.perform(patch("/accounts/{id}/" + action, ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(action.equals("block") ? "BLOCKED" : "ACTIVE"))
                .andExpect(jsonPath("$.balance").value(100.00));
        verifyNoInteractions(transactionRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"block", "unblock", "close"})
    void shouldProtectStatusEndpoints(String action) throws Exception {
        mvc.perform(patch("/accounts/{id}/" + action, ownAccount.getId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/accounts/{id}/" + action, otherAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(patch("/accounts/{id}/" + action, UUID.randomUUID()).header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(patch("/accounts/invalid/" + action).header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        assertEquals(AccountStatus.ACTIVE, otherAccount.getStatus());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void shouldCloseOwnAccountAndKeepReadAccess() throws Exception {
        ownAccount.setBalance(BigDecimal.ZERO);
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        mvc.perform(patch("/accounts/{id}/close", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balance").value(0));
        mvc.perform(get("/accounts/{id}", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/accounts/{id}/transactions", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldReturn409ForInvalidClosure() throws Exception {
        for (AccountStatus initial : AccountStatus.values()) {
            ownAccount.setStatus(initial);
            ownAccount.setBalance(initial == AccountStatus.ACTIVE ? new BigDecimal("0.01") : BigDecimal.ZERO);
            mvc.perform(patch("/accounts/{id}/close", ownAccount.getId()).header("Authorization", bearer))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
            assertEquals(initial, ownAccount.getStatus());
        }
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"block", "unblock"})
    void shouldReturnConflictForInvalidStatusTransition(String action) throws Exception {
        for (AccountStatus initial : List.of(action.equals("block") ? AccountStatus.BLOCKED : AccountStatus.ACTIVE,
                AccountStatus.CLOSED)) {
            ownAccount.setStatus(initial);
            mvc.perform(patch("/accounts/{id}/" + action, ownAccount.getId()).header("Authorization", bearer))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
            assertEquals(initial, ownAccount.getStatus());
        }
        verify(accountRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit", "withdraw", "transfer"})
    void shouldRejectBlockedAccountMovementsButAllowReading(String operation) throws Exception {
        ownAccount.setStatus(AccountStatus.BLOCKED);
        String path = operation.equals("transfer") ? "/accounts/transfer"
                : "/accounts/" + ownAccount.getId() + "/" + operation;
        mvc.perform(post(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":1.00}
                                """.formatted(ownAccount.getId(), otherAccount.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(get("/accounts/{id}", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        mvc.perform(get("/accounts/{id}/transactions", ownAccount.getId()).header("Authorization", bearer))
                .andExpect(status().isOk());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-0.01", "0.001", "0.015", "1.000", "10000000000000", "1e13"})
    void shouldReturn400ForInvalidMoneyOnEveryOperation(String amount) throws Exception {
        for (String operation : List.of("deposit", "withdraw", "transfer")) {
            String path = operation.equals("transfer") ? "/accounts/transfer"
                    : "/accounts/" + ownAccount.getId() + "/" + operation;
            String body = """
                    {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s}
                    """.formatted(ownAccount.getId(), otherAccount.getId(), amount);
            mvc.perform(post(path).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.path").value(path));
        }
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit", "transfer"})
    void shouldReturn409WhenResultingBalanceExceedsDatabaseLimit(String operation) throws Exception {
        Account credited = operation.equals("deposit") ? ownAccount : otherAccount;
        credited.setBalance(new BigDecimal("9999999999999.99"));
        String path = operation.equals("deposit") ? "/accounts/" + ownAccount.getId() + "/deposit" : "/accounts/transfer";
        String body = """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":0.01}
                """.formatted(ownAccount.getId(), otherAccount.getId());
        mvc.perform(post(path).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertEquals(new BigDecimal("9999999999999.99"), credited.getBalance());
        if (operation.equals("transfer")) assertEquals(new BigDecimal("100.00"), ownAccount.getBalance());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    private String signedToken(String subject, String secret, long expiration) {
        return Jwts.builder().subject(subject).expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private Customer customer(String email) {
        return Customer.builder().id(UUID.randomUUID()).fullName("Customer").email(email)
                .cpf("12345678901").phone("81999999999").birthDate(LocalDate.of(2000, 1, 1))
                .createdAt(LocalDateTime.now()).build();
    }

    private Account account(Customer customer) {
        return Account.builder().id(UUID.randomUUID()).customer(customer).accountNumber("12345678")
                .agency("0001").status(AccountStatus.ACTIVE).balance(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now()).build();
    }
}

package com.italo.bankingapi.security;

import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.CustomerRole;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.service.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(AdminAuthorizationIntegrationTest.ProbeConfiguration.class)
class AdminAuthorizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CustomerRepository customers;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminProbeService probeService;
    @Autowired jakarta.persistence.EntityManager entityManager;
    Customer customer;
    Customer admin;

    @BeforeEach
    void setUp() {
        customer = create("77777777777", CustomerRole.CUSTOMER);
        admin = create("88888888888", CustomerRole.ADMIN);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void requiresAuthenticationAndAdminAuthority() throws Exception {
        mvc.perform(get("/admin/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/probe").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value("/admin/probe"));
        mvc.perform(get("/admin/probe").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(content().string("allowed"));
    }

    @Test
    void exposesOnlyOwnIdentityAndCurrentRole() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", bearer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.role").value("CUSTOMER")).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/auth/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void sameTokenReflectsRolePromotionAndRevocation() throws Exception {
        String token = bearer(customer);
        mvc.perform(get("/admin/probe").header("Authorization", token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?", customer.getId());
        entityManager.clear();
        mvc.perform(get("/admin/probe").header("Authorization", token)).andExpect(status().isOk());
        jdbc.update("UPDATE customers SET role = 'CUSTOMER' WHERE id = ?", customer.getId());
        entityManager.clear();
        mvc.perform(get("/admin/probe").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void publicRegistrationCannotAssignAdminRole() throws Exception {
        mvc.perform(post("/customers").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Public User","cpf":"12345678909","email":"role-registration@test.com",
                 "password":"test-password","birthDate":"2000-01-01","phone":"81999999999","role":"ADMIN"}
                """ )).andExpect(status().isCreated());
        assertEquals(CustomerRole.CUSTOMER, customers.findByEmail("role-registration@test.com").orElseThrow().getRole());
    }

    @Test
    void profileUpdateCannotChangeRoleAndAdminStillNeedsOwnership() throws Exception {
        mvc.perform(put("/customers/{id}", customer.getId()).header("Authorization", bearer(customer))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Updated User","email":"updated-role@test.com","birthDate":"2000-01-01","phone":"81999999999","role":"ADMIN"}
                """ )).andExpect(status().isOk());
        assertEquals(CustomerRole.CUSTOMER, customers.findById(customer.getId()).orElseThrow().getRole());
        mvc.perform(get("/customers/{id}", customer.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void methodSecurityAlsoProtectsDirectServiceCalls() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(customer, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        assertThrows(AccessDeniedException.class, () -> probeService.execute());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertEquals("allowed", probeService.execute());
    }

    @Test
    void tokenRoleClaimCannotOverrideDatabaseRole() throws Exception {
        String token = io.jsonwebtoken.Jwts.builder().subject(customer.getId().toString())
                .claim("role", "ADMIN").expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/admin/probe").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void migrationProvidesSafeDefaultForDatabaseInserts() {
        var id = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers (id, full_name, cpf, email, password, birth_date, created_at)
                VALUES (?, 'Legacy insert', '99999999999', 'legacy-role@test.com', 'test-only', '2000-01-01', CURRENT_TIMESTAMP)
                """, id);
        assertEquals("CUSTOMER", jdbc.queryForObject("SELECT role FROM customers WHERE id = ?", String.class, id));
    }

    private Customer create(String cpf, CustomerRole role) {
        return customers.saveAndFlush(Customer.builder().fullName("Role test").cpf(cpf).email(cpf + "@role.test")
                .password("test-only").birthDate(LocalDate.of(2000, 1, 1)).createdAt(LocalDateTime.now()).role(role).build());
    }
    private String bearer(Customer user) { return "Bearer " + jwt.generateToken(user.getId()); }

    // Test-only endpoints exercise the reserved namespace without adding a fake production operation.
    @TestConfiguration
    static class ProbeConfiguration {
        @Bean AdminProbeController adminProbeController() { return new AdminProbeController(); }
        @Bean AdminProbeService adminProbeService() { return new AdminProbeService(); }
    }
    @RestController
    static class AdminProbeController {
        @GetMapping("/admin/probe")
        public String probe() { return "allowed"; }
    }
    static class AdminProbeService {
        @PreAuthorize("hasRole('ADMIN')")
        public String execute() { return "allowed"; }
    }
}

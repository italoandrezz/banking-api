package com.italo.bankingapi.security;

import com.italo.bankingapi.config.SecurityConfig;
import com.italo.bankingapi.config.filter.*;
import com.italo.bankingapi.config.security.*;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.enums.CustomerRole;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = Security7ConfigurationTest.Probe.class, properties = {
        "jwt.secret=synthetic-security-test-key-01234567890123456789", "jwt.expiration=60000"})
@Import({Security7ConfigurationTest.Probe.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, JwtService.class,
        CorrelationIdFilter.class, UnexpectedErrorFilter.class})
class Security7ConfigurationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean CustomerRepository customers;
    @RestController static class Probe {
        @GetMapping("/admin/migration-probe") String admin() { return "allowed"; }
    }
    @Test void currentDatabaseRoleControlsAdminAccessAndResponsesRemainCorrelated() throws Exception {
        var customer = new Customer();
        customer.setId(UUID.randomUUID()); customer.setRole(CustomerRole.CUSTOMER);
        when(customers.findById(customer.getId())).thenReturn(Optional.of(customer));
        String token = jwt.generateToken(customer.getId());
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/admin/migration-probe").header("X-Correlation-ID", id))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-ID", id))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/admin/migration-probe").header("Authorization", "Bearer " + token).header("X-Correlation-ID", id))
                .andExpect(status().isForbidden()).andExpect(header().string("X-Correlation-ID", id))
                .andExpect(jsonPath("$.status").value(403));
        customer.setRole(CustomerRole.ADMIN);
        mvc.perform(get("/admin/migration-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("allowed"));
        customer.setRole(CustomerRole.CUSTOMER);
        mvc.perform(get("/admin/migration-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}

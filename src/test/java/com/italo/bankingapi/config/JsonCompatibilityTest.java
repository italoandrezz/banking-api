package com.italo.bankingapi.config;

import com.italo.bankingapi.dto.account.AccountResponse;
import com.italo.bankingapi.dto.account.DepositRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.dto.transaction.TransactionPageResponse;
import com.italo.bankingapi.dto.transaction.TransactionResponse;
import com.italo.bankingapi.enums.AccountStatus;
import com.italo.bankingapi.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JsonCompatibilityTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    Jackson2AutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class));

    @Test void mvcUsesJackson3AndPreservesAccountAndStoredReplayJson() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var legacy = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            var account = AccountResponse.builder().id(UUID.randomUUID()).customerId(UUID.randomUUID())
                    .accountNumber("123").agency("0001").balance(new BigDecimal("9999999999999.99"))
                    .status(AccountStatus.ACTIVE).createdAt(TIME).build();
            var converter = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters().stream()
                    .filter(c -> c.canWrite(AccountResponse.class, MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
            assertThat(converter).isInstanceOf(JacksonJsonHttpMessageConverter.class);
            var output = new MockHttpOutputMessage();
            ((JacksonJsonHttpMessageConverter) converter).write(account, MediaType.APPLICATION_JSON, output);
            var json = mapper.readTree(output.getBodyAsString());
            assertThat(json.get("createdAt").asText()).isEqualTo("2026-01-02T03:04:05");
            assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(output.getBodyAsString()).contains("9999999999999.99");
            assertThat(json).isEqualTo(mapper.readTree(legacy.writeValueAsString(account)));
            assertThat(mapper.readValue(legacy.writeValueAsString(account), AccountResponse.class).getBalance())
                    .isEqualByComparingTo(account.getBalance());
            assertThat(legacy.readValue(output.getBodyAsString(), AccountResponse.class).getCreatedAt()).isEqualTo(TIME);
        });
    }

    @Test void dateAndNullableFieldsKeepTheirExistingRepresentation() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var customer = CustomerResponse.builder().birthDate(LocalDate.of(2000, 1, 2)).createdAt(TIME).build();
            var json = mapper.readTree(mapper.writeValueAsString(customer));
            assertThat(json.get("birthDate").asText()).isEqualTo("2000-01-02");
            assertThat(json.get("phone").isNull()).isTrue();
            assertThat(mapper.readValue(mapper.writeValueAsString(customer), CustomerResponse.class).getBirthDate())
                    .isEqualTo(customer.getBirthDate());
        });
    }

    @Test void monetaryInputRetainsPrecisionAndAcceptsPreviouslyIgnoredFields() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var request = mapper.readValue("{\"amount\":9999999999999.99,\"extra\":true}", DepositRequest.class);
            assertThat(request.getAmount()).isEqualTo(new BigDecimal("9999999999999.99"));
        });
    }

    @Test void paginationAndTransactionEnumsPreserveTheirEnvelope() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var transaction = TransactionResponse.builder().type(TransactionType.DEPOSIT)
                    .amount(new BigDecimal("10.00")).createdAt(TIME).build();
            var page = new TransactionPageResponse(List.of(transaction), 0, 20, 1, 1);
            var json = mapper.readTree(mapper.writeValueAsString(page));
            assertThat(json.size()).isEqualTo(5);
            assertThat(json.get("page").asInt()).isZero();
            assertThat(json.get("size").asInt()).isEqualTo(20);
            assertThat(json.get("totalElements").asLong()).isEqualTo(1);
            assertThat(json.get("totalPages").asInt()).isEqualTo(1);
            assertThat(json.get("content").get(0).get("type").asText()).isEqualTo("DEPOSIT");
            assertThat(json).isEqualTo(mapper.readTree(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(page)));
        });
    }

    @Test void errorEnvelopesMatchAcrossMvcAndLegacyFilterMappers() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var legacy = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            for (int status : new int[]{400, 401, 403, 500, 503}) {
                var error = ErrorResponse.builder().timestamp(TIME).status(status).error("Error")
                        .message("Safe message").path("/accounts").build();
                var json = mapper.readTree(mapper.writeValueAsString(error));
                assertThat(json.size()).isEqualTo(5);
                assertThat(json.get("timestamp").asText()).isEqualTo("2026-01-02T03:04:05");
                assertThat(json.get("status").asInt()).isEqualTo(status);
                assertThat(json).isEqualTo(mapper.readTree(legacy.writeValueAsString(error)));
            }
        });
    }
}

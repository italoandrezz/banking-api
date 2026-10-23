package com.italo.bankingapi.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.italo.bankingapi.config.filter.CorrelationIdFilter;
import com.italo.bankingapi.config.filter.JwtAuthenticationFilter;
import com.italo.bankingapi.config.filter.UnexpectedErrorFilter;
import com.italo.bankingapi.config.security.JwtAuthenticationEntryPoint;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.handler.GlobalExceptionHandler;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.service.JwtService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UnexpectedErrorFilterTest {
    private static final String PRIVATE = "password=private JWT=private SELECT balance FROM customers";
    private static final String ID = UUID.randomUUID().toString();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UnexpectedErrorFilter errors = new UnexpectedErrorFilter(mapper);

    @RestController
    static class Controller {
        RuntimeException failure;
        Controller(RuntimeException failure) { this.failure = failure; }
        @GetMapping("/probe")
        String probe() { throw failure; }
    }

    private void mvcFailure(RuntimeException failure, int status, String message) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Controller(failure))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter(), errors).build();
        var result = mvc.perform(get("/probe").header(CorrelationIdFilter.HEADER, ID))
                .andExpect(status().is(status))
                .andExpect(header().string(CorrelationIdFilter.HEADER, ID))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/probe"))
                .andExpect(jsonPath("$.trace").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PRIVATE);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void programmingAndSqlGrammarErrorsRemain500() throws Exception {
        mvcFailure(new NullPointerException(PRIVATE), 500, "An unexpected error occurred.");
        mvcFailure(new BadSqlGrammarException(PRIVATE, PRIVATE, new SQLException(PRIVATE, "42601")),
                500, "An unexpected error occurred.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08006", "57P01", "57P02", "57P03"})
    void wrappedPostgresConnectionFailuresAre503(String state) throws Exception {
        mvcFailure(new RuntimeException(new SQLException(PRIVATE, state)), 503,
                "Service temporarily unavailable.");
    }

    @Test
    void connectionPoolFailureIs503AndBusinessConflictUnchanged() throws Exception {
        mvcFailure(new DataAccessResourceFailureException(PRIVATE), 503, "Service temporarily unavailable.");
        mvcFailure(new ConflictException("Existing business conflict."), 409, "Existing business conflict.");
    }

    @Test
    void jwtRepositoryFailuresAreNotReportedAsInvalidTokens() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{
                new IllegalArgumentException(PRIVATE), new DataAccessResourceFailureException(PRIVATE)}) {
            var jwt = mock(JwtService.class);
            var repository = mock(CustomerRepository.class);
            var entry = mock(JwtAuthenticationEntryPoint.class);
            UUID customer = UUID.randomUUID();
            when(jwt.extractCustomerId("opaque")).thenReturn(customer);
            when(repository.findById(customer)).thenThrow(failure);
            var filter = new JwtAuthenticationFilter(jwt, repository, entry);
            var request = new MockHttpServletRequest("GET", "/probe");
            request.addHeader("Authorization", "Bearer opaque");
            request.addHeader(CorrelationIdFilter.HEADER, ID);
            var response = new MockHttpServletResponse();
            new CorrelationIdFilter().doFilter(request, response, (req, res) ->
                    errors.doFilter(req, res, (r, s) -> filter.doFilter(r, s,
                            (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Chain must stop"); })));
            assertThat(response.getStatus()).isEqualTo(failure instanceof IllegalArgumentException ? 500 : 503);
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(ID);
            assertThat(response.getContentAsString()).doesNotContain(PRIVATE, "opaque");
            verifyNoInteractions(entry);
        }
    }

    @Test
    void unexpectedFailuresAreLoggedWithoutThrowableOrSensitiveValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(UnexpectedErrorFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvcFailure(new IllegalStateException(PRIVATE), 500, "An unexpected error occurred.");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
            assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain(PRIVATE);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void committedResponsesAndFatalErrorsAreNotSwallowed() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        response.getWriter().write("already sent");
        response.flushBuffer();
        assertThrows(ServletException.class, () -> errors.doFilter(request, response,
                (req, res) -> { throw new ServletException(PRIVATE); }));
        assertThat(response.getContentAsString()).isEqualTo("already sent");
        assertThrows(AssertionError.class, () -> errors.doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> { throw new AssertionError(PRIVATE); }));
    }
}

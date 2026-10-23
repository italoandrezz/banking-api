package com.italo.bankingapi.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.italo.bankingapi.dto.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Outside security/MVC, but inside correlation handling. Handled business errors pass unchanged. */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class UnexpectedErrorFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedErrorFilter.class);
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException exception) {
            HttpStatus status = connectionUnavailable(exception)
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
            // Never pass the original throwable, message, SQL, URL or request data to a logger.
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                LOG.warn("database_connection_unavailable");
            } else {
                LOG.error("unexpected_request_failure type={}", exception.getClass().getName());
            }
            // A partially transmitted response cannot safely be replaced with another JSON document.
            if (response.isCommitted()) throw exception;
            response.resetBuffer(); // Preserve X-Correlation-ID and security headers.
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.builder()
                    .timestamp(LocalDateTime.now()).status(status.value()).error(status.getReasonPhrase())
                    .message(status == HttpStatus.SERVICE_UNAVAILABLE
                            ? "Service temporarily unavailable." : "An unexpected error occurred.")
                    .path(request.getRequestURI()).build());
        }
    }

    private static boolean connectionUnavailable(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof JDBCConnectionException
                    || cause instanceof SQLTransientConnectionException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                // Connection failures and PostgreSQL shutdown/cannot-connect states only.
                if (state != null && (state.startsWith("08") || state.equals("57P01")
                        || state.equals("57P02") || state.equals("57P03"))) return true;
            }
        }
        return false;
    }
}

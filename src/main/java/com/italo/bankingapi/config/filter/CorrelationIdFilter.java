package com.italo.bankingapi.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/** Servlet filter preceding security, so rejected requests are also correlated. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static boolean isValid(String value) {
        return value != null && value.length() == 36
                && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            var headers = Collections.list(request.getHeaders(HEADER));
            id = headers.size() == 1 && isValid(headers.get(0)) ? headers.get(0) : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        var previous = MDC.getCopyOfContextMap();
        long start = System.nanoTime();
        boolean failed = true;
        try {
            MDC.clear();
            MDC.put(MDC_KEY, id);
            response.setHeader(HEADER, id);
            chain.doFilter(request, response);
            failed = false;
        } finally {
            MDC.put("httpStatus", Integer.toString(failed ? 500 : response.getStatus()));
            MDC.put("durationMs", Long.toString((System.nanoTime() - start) / 1_000_000));
            LOG.info("http_request_completed");
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
    }
}

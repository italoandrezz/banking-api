package com.italo.bankingapi.observability;

import com.italo.bankingapi.config.filter.CorrelationIdFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"invalid", "12345678901", "user@example.test", "bad\r\nheader", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa-extra"})
    void replacesUnsafeOrAbsentIds(String value) throws Exception {
        var request = new MockHttpServletRequest();
        if (value != null) request.addHeader(CorrelationIdFilter.HEADER, value);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertTrue(CorrelationIdFilter.isValid(MDC.get("correlationId"))));
        assertTrue(CorrelationIdFilter.isValid(response.getHeader(CorrelationIdFilter.HEADER)));
        assertNotEquals(value, response.getHeader(CorrelationIdFilter.HEADER));
        assertNull(MDC.get("correlationId"));
    }

    @Test void rejectsDuplicateHeadersAndExcessiveLength() throws Exception {
        for (boolean duplicate : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest();
            String id = duplicate ? UUID.randomUUID().toString() : "a".repeat(10000);
            request.addHeader(CorrelationIdFilter.HEADER, id);
            if (duplicate) request.addHeader(CorrelationIdFilter.HEADER, UUID.randomUUID().toString());
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertNotEquals(id, response.getHeader(CorrelationIdFilter.HEADER));
            assertTrue(CorrelationIdFilter.isValid(response.getHeader(CorrelationIdFilter.HEADER)));
        }
    }

    @Test void restoresOuterContextEvenWhenRequestFails() {
        MDC.put("outer", "preserved");
        try {
            assertThrows(ServletException.class, () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> { assertNull(MDC.get("outer")); throw new ServletException("synthetic"); }));
            assertNull(MDC.get("correlationId"));
            assertEquals("preserved", MDC.get("outer"));
        } finally { MDC.clear(); }
    }

    @Test void concurrentRequestsKeepTheirOwnIdsAndCleanWorkerThreads() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var barrier = new CyclicBarrier(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> {
                String id = UUID.randomUUID().toString();
                var request = new MockHttpServletRequest(); request.addHeader(CorrelationIdFilter.HEADER, id);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> {
                    try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception e) { throw new ServletException(e); }
                    assertEquals(id, MDC.get("correlationId"));
                });
                assertEquals(id, response.getHeader(CorrelationIdFilter.HEADER));
                assertNull(MDC.getCopyOfContextMap());
                return null;
            }));
            for (var task : tasks) task.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}

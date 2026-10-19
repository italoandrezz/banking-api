package com.italo.bankingapi.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.italo.bankingapi.config.logging.SafeJsonLogFormatter;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SafeJsonLogFormatterTest {
    @Test void discardsMessagesArgumentsExceptionsMdcAndFluentSensitiveFields() throws Exception {
        String sensitive = "password=private-secret jwt=eySensitive CPF=12345678901 person@example.test balance=1234.56";
        var event = new LoggingEvent();
        event.setTimeStamp(System.currentTimeMillis()); event.setLevel(Level.ERROR);
        event.setLoggerName("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
        event.setMessage(sensitive + " {}"); event.setArgumentArray(new Object[]{sensitive});
        event.setMDCPropertyMap(Map.of("password", sensitive, "correlationId", sensitive));
        event.addKeyValuePair(new KeyValuePair("token", sensitive));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException(sensitive, new RuntimeException(sensitive))));
        String json = new SafeJsonLogFormatter().format(event);
        var parsed = new ObjectMapper().readTree(json);
        assertEquals("ERROR", parsed.path("level").asText());
        assertEquals("java.lang.IllegalStateException", parsed.path("exceptionType").asText());
        for (String forbidden : new String[]{"private-secret", "eySensitive", "12345678901", "person@example.test", "1234.56", "stack_trace"})
            assertFalse(json.contains(forbidden));
        assertFalse(parsed.has("message"));
        assertTrue(parsed.path("correlationId").isNull());
    }
}

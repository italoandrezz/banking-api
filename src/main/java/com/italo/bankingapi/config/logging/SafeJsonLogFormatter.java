package com.italo.bankingapi.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.italo.bankingapi.config.filter.CorrelationIdFilter;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/** Native Boot logging extension: allowlisted metadata, never arbitrary event content. */
public class SafeJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private final JsonWriter<ILoggingEvent> writer = JsonWriter.of(members -> {
        members.add("timestamp", event -> event.getInstant().toString());
        members.add("level", event -> event.getLevel().toString());
        members.add("logger", event -> technicalName(event.getLoggerName()));
        members.add("event", event -> isHttp(event) ? "http_request_completed" : "technical_event");
        members.add("correlationId", event -> {
            String id = event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY);
            return CorrelationIdFilter.isValid(id) ? id : null;
        });
        members.add("status", event -> isHttp(event) ? number(event, "httpStatus", 100, 599) : null);
        members.add("durationMs", event -> isHttp(event) ? number(event, "durationMs", 0, Long.MAX_VALUE) : null);
        members.add("exceptionType", event -> event.getThrowableProxy() == null ? null
                : technicalName(event.getThrowableProxy().getClassName()));
    });

    private static boolean isHttp(ILoggingEvent event) {
        return CorrelationIdFilter.class.getName().equals(event.getLoggerName())
                && "http_request_completed".equals(event.getMessage());
    }

    private static Long number(ILoggingEvent event, String key, long min, long max) {
        try {
            long value = Long.parseLong(event.getMDCPropertyMap().get(key));
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String technicalName(String name) {
        return name != null && name.length() <= 200 && name.matches("[a-zA-Z_$][a-zA-Z0-9_.$]*")
                ? name : "unknown";
    }

    @Override public String format(ILoggingEvent event) { return writer.writeToString(event) + "\n"; }
}

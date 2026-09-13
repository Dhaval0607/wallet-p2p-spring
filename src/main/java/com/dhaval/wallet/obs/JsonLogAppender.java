package com.dhaval.wallet.obs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.event.KeyValuePair;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;

/**
 * Writes every log record as one line of JSON, to stdout for the host's log
 * drain and simultaneously into {@link LogRing} for the public {@code /logs}
 * stream.
 *
 * <p>Hand-rolled rather than pulled from a library: the field names have to match
 * what the log viewer and the burst script expect ({@code event},
 * {@code correlation_id}), and serializing eight known fields is less code than
 * configuring an encoder to rename them.
 *
 * <p>Structured fields arrive as SLF4J 2 key-value pairs, so a caller writes
 * {@code log.atInfo().addKeyValue("event", "transfer_succeeded").log(...)} and
 * the value lands as a real JSON field rather than inside the message string.
 */
public class JsonLogAppender extends AppenderBase<ILoggingEvent> {

    private static final PrintStream OUT = System.out;

    @Override
    protected void append(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        sb.append(',');
        field(sb, "level", event.getLevel().toString());
        sb.append(',');
        field(sb, "msg", event.getFormattedMessage());
        sb.append(',');
        field(sb, "logger", shortLogger(event.getLoggerName()));

        // Correlation id rides in MDC so no call site has to remember to pass it.
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> e : mdc.entrySet()) {
                sb.append(',');
                field(sb, e.getKey(), e.getValue());
            }
        }

        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair kv : event.getKeyValuePairs()) {
                sb.append(',');
                if (kv.value instanceof Number || kv.value instanceof Boolean) {
                    sb.append('"').append(escape(kv.key)).append("\":").append(kv.value);
                } else {
                    field(sb, kv.key, String.valueOf(kv.value));
                }
            }
        }

        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) {
            sb.append(',');
            field(sb, "error", t.getClassName() + ": " + t.getMessage());
            sb.append(',');
            field(sb, "stack", abbreviate(ThrowableProxyUtil.asString(t)));
        }
        sb.append('}');

        String line = sb.toString();
        OUT.println(line);
        LogRing.get().append(line);
    }

    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
    }

    private static String shortLogger(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String abbreviate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

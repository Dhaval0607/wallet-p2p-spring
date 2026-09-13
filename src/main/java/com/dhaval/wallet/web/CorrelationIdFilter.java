package com.dhaval.wallet.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Assigns a correlation id to every request and puts it in MDC, so the JSON log
 * appender stamps it onto every line without any call site having to pass it.
 *
 * <p>The id is accepted from the caller and echoed back on the response, which is
 * what lets the burst script pin an id client-side and then filter the public log
 * stream down to exactly its own run.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = newId();
        } else if (id.length() > 128) {
            id = id.substring(0, 128);
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat reuses threads; a leaked id would mislabel the next request.
            MDC.remove(MDC_KEY);
        }
    }

    public static String currentId() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "" : id;
    }

    private static String newId() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return "req_" + HexFormat.of().formatHex(b);
    }
}

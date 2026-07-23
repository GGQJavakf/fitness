package com.aifitness.assistant.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Request-Id";
    private static final String MDC_KEY = "requestId";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String previousRequestId = MDC.get(MDC_KEY);
        String requestId = requestIdFor(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        }
    }

    private String requestIdFor(String inboundRequestId) {
        return inboundRequestId != null && VALID_REQUEST_ID.matcher(inboundRequestId).matches()
                ? inboundRequestId
                : UUID.randomUUID().toString();
    }
}

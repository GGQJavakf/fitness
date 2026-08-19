package com.aifitness.assistant.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
        } catch (Exception exception) {
            if (response.isCommitted()) {
                rethrow(exception);
            }
            LOGGER.error("Unexpected server error [requestId={}, type={}]", requestId,
                    exception.getClass().getName());
            response.resetBuffer();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ApiError error = new ApiError(
                    ErrorCode.INTERNAL_ERROR, "服务器内部错误", List.of(), Map.of(), false);
            OBJECT_MAPPER.writeValue(response.getWriter(),
                    new ApiErrorResponse(error, new ErrorMeta(requestId)));
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        }
    }

    private void rethrow(Exception exception) throws ServletException, IOException {
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        if (exception instanceof ServletException servletException) {
            throw servletException;
        }
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new ServletException(exception);
    }

    private String requestIdFor(String inboundRequestId) {
        return inboundRequestId != null && VALID_REQUEST_ID.matcher(inboundRequestId).matches()
                ? inboundRequestId
                : UUID.randomUUID().toString();
    }
}

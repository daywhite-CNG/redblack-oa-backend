package com.redblack.office.api;

import com.redblack.common.trace.TraceHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    static final String ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceHeaders.REQUEST_ID);
        String requestId = incoming != null && incoming.length() <= 100 && incoming.matches("[A-Za-z0-9._:-]+")
                ? incoming : "req_" + UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(TraceHeaders.REQUEST_ID, requestId);
        MDC.put("requestId", requestId);
        try { chain.doFilter(request, response); } finally { MDC.remove("requestId"); }
    }
}

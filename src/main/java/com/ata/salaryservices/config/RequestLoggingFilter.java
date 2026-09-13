package com.ata.salaryservices.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every HTTP request to the console: method, URI with query string, response status, duration and client address.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            String query = request.getQueryString();
            log.info("{} {}{} -> {} ({} ms) from {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    query != null ? "?" + query : "",
                    response.getStatus(),
                    System.currentTimeMillis() - start,
                    request.getRemoteAddr());
        }
    }
}

package com.ata.salaryservices.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Logs every HTTP request to the console: method, URI with query string, response status, duration and client address.
 * At DEBUG level it also logs the response body (JSON/text only, cut to {@code app.logging.max-body-length} characters).
 * Kubernetes probe calls to {@code /actuator/**} are skipped, otherwise they flood the log every few seconds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final int maxBodyLength;

    public RequestLoggingFilter(@Value("${app.logging.max-body-length:1000}") int maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Buffers the body in memory so it can be read after the controller has written it
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, cachingResponse);
        } finally {
            String query = request.getQueryString();
            log.info("{} {}{} -> {} ({} ms) from {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    query != null ? "?" + query : "",
                    cachingResponse.getStatus(),
                    System.currentTimeMillis() - start,
                    request.getRemoteAddr());
            if (log.isDebugEnabled()) {
                log.debug("Response body: {}", describeBody(cachingResponse));
            }
            // Required: sends the buffered body to the client, otherwise the client gets an empty response
            cachingResponse.copyBodyToResponse();
        }
    }

    private String describeBody(ContentCachingResponseWrapper response) {
        byte[] body = response.getContentAsByteArray();
        if (body.length == 0) {
            return "(empty)";
        }
        String contentType = response.getContentType();
        if (contentType == null || !(contentType.contains("json") || contentType.startsWith("text/"))) {
            return "(" + body.length + " bytes of " + contentType + ")";
        }
        Charset charset = response.getCharacterEncoding() != null
                ? Charset.forName(response.getCharacterEncoding())
                : StandardCharsets.UTF_8;
        String text = new String(body, charset);
        return text.length() <= maxBodyLength
                ? text
                : text.substring(0, maxBodyLength) + "... (" + text.length() + " chars total)";
    }
}

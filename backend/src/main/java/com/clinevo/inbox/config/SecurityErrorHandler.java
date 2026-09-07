package com.clinevo.inbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Component
public class SecurityErrorHandler {
    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void authenticationRequired(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        write(request, response, 401, "Unauthorized", "Authentication is required.");
    }

    public void accessDenied(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        write(request, response, 403, "Forbidden", "The authenticated identity is not authorized for this operation.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "error", error,
                "message", message,
                "path", request.getRequestURI(),
                "requestId", RequestCorrelationFilter.requestId(request)
        ));
    }
}

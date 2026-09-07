package com.clinevo.inbox.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ReviewerIdentityService {
    private final String authMode;

    public ReviewerIdentityService(@Value("${clinevo.auth.mode:demo}") String authMode) {
        this.authMode = authMode == null ? "demo" : authMode.trim().toLowerCase();
    }

    public String resolve(String requestedReviewer) {
        if ("oidc".equals(authMode)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                throw new IllegalStateException("Authenticated reviewer identity is unavailable");
            }
            String principal = authentication.getName();
            if (principal == null || principal.isBlank()) throw new IllegalStateException("Authenticated reviewer identity is blank");
            return principal;
        }
        if (requestedReviewer == null || requestedReviewer.isBlank()) {
            throw new IllegalArgumentException("reviewer is required in demo authentication mode");
        }
        return requestedReviewer.trim();
    }
}

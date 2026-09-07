package com.clinevo.inbox.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewerIdentityServiceTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void oidcModeIgnoresSpoofedBodyReviewer() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "idp-reviewer", "n/a", List.of(new SimpleGrantedAuthority("ROLE_REVIEWER"))));
        ReviewerIdentityService service = new ReviewerIdentityService("oidc");
        assertThat(service.resolve("spoofed-reviewer")).isEqualTo("idp-reviewer");
    }

    @Test
    void demoModeUsesExplicitReviewer() {
        assertThat(new ReviewerIdentityService("demo").resolve(" candidate ")).isEqualTo("candidate");
        assertThatThrownBy(() -> new ReviewerIdentityService("demo").resolve(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}

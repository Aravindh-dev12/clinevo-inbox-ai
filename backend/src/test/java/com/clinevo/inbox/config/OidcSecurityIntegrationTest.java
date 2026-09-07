package com.clinevo.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "clinevo.auth.mode=oidc",
        "clinevo.auth.oidc.issuer-uri=https://issuer.example.test",
        "clinevo.auth.oidc.jwk-set-uri=https://issuer.example.test/jwks",
        "clinevo.auth.oidc.audience=clinevo-inbox"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OidcSecurityIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void healthIsPublicAndGetsCorrelationId() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(header().exists("X-Request-ID"));
    }

    @Test
    void inboxRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/inbox"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void reviewerRoleCanReadInbox() throws Exception {
        mvc.perform(get("/api/inbox").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void unrelatedRoleCannotReadInbox() throws Exception {
        mvc.perform(get("/api/inbox").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }
}

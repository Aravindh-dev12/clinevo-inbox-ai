package com.clinevo.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyInterceptorTest {
    private final ApiKeyInterceptor interceptor = new ApiKeyInterceptor("test-secret");

    @Test
    void getRequestsRemainReadable() throws Exception {
        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/inbox"), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void writeRequiresConfiguredKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/inbox/1/review");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void validKeyAllowsWrite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/inbox/1/review");
        request.addHeader("X-API-Key", "test-secret");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}

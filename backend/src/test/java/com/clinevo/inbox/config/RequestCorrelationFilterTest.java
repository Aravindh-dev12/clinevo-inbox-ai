package com.clinevo.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "walkthrough-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("walkthrough-123");
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isNotBlank().doesNotContain(" ");
    }
}

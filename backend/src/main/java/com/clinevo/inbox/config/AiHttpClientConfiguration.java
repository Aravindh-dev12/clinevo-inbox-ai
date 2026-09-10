package com.clinevo.inbox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the synchronous client used for Spring Boot -> FastAPI calls.
 *
 * <p>The JDK HTTP client can attempt a clear-text HTTP/2 (h2c) upgrade. Uvicorn
 * serves HTTP/1.1 in this stack, so an explicit HTTP/1.1-compatible request
 * factory keeps JSON and multipart request bodies deterministic.</p>
 */
@Configuration(proxyBeanMethods = false)
public class AiHttpClientConfiguration {

    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${clinevo.ai-connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${clinevo.ai-read-timeout-ms:120000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeout(connectTimeoutMs, 5_000));
        requestFactory.setReadTimeout(safeTimeout(readTimeoutMs, 120_000));
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static int safeTimeout(int configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }
}

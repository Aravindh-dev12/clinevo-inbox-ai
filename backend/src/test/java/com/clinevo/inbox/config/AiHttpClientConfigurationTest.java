package com.clinevo.inbox.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiHttpClientConfigurationTest {

    @Test
    void sendsJsonOverHttp11WithoutH2cUpgrade() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/process-text", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RestClient client = new AiHttpClientConfiguration()
                    .restClientBuilder(1_000, 5_000)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();

            JsonNode response = client.post()
                    .uri("/process-text")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("source_name", "email:42", "text", "Synthetic safety report"))
                    .retrieve()
                    .body(JsonNode.class);

            assertThat(response).isNotNull();
            assertThat(response.path("status").asText()).isEqualTo("ok");
            assertThat(upgrade.get()).isNull();
            assertThat(contentType.get()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(requestBody.get()).contains("\"source_name\":\"email:42\"");
            assertThat(requestBody.get()).contains("\"text\":\"Synthetic safety report\"");
        } finally {
            server.stop(0);
        }
    }
}

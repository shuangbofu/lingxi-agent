package top.fusb.lingxi.runtime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.ModelEndpointCheckResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class ModelEndpointServiceTest {

    @Test
    void createsServiceThroughSpringConstructorInjection() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ModelEndpointService.class)
                .run(context -> assertThat(context).hasSingleBean(ModelEndpointService.class));
    }

    @Test
    void checksOpenAiCompatibleEndpointIndependentlyFromRuntime() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"deepseek-chat\"},{\"id\":\"deepseek-reasoner\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ModelEndpointService service = new ModelEndpointService(new ObjectMapper());

            ModelEndpointCheckResponse result = service.check(
                    "sk-test", "http://127.0.0.1:" + server.getAddress().getPort(), List.of());

            assertTrue(result.isSuccess());
            assertEquals("检测通过", result.getMessage());
            assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), result.getModels());
            assertEquals("Bearer sk-test", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void convertsAuthenticationJsonToReadableMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"Invalid Authentication\",\"type\":\"invalid_authentication_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ModelEndpointService service = new ModelEndpointService(new ObjectMapper());

            ModelEndpointCheckResponse result = service.check(
                    "invalid-key", "http://127.0.0.1:" + server.getAddress().getPort(), List.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).isEqualTo("认证失败，请检查 API Key");
        } finally {
            server.stop(0);
        }
    }
}

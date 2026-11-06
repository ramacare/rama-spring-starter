package org.rama.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.api.Api;
import org.rama.repository.api.ApiHeaderSetRepository;
import org.rama.repository.api.ApiRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class GenericApiServiceTest {

    private static final String API_ID = "test-api";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiHeaderSetRepository apiHeaderSetRepository;

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GenericApiService serviceWithResponseTimeout(Duration responseTimeout) {
        return new GenericApiService(apiRepository, apiHeaderSetRepository, WebClient.builder(),
                JsonMapper.builder().build(), Duration.ofSeconds(5), responseTimeout);
    }

    private void stubApi(String path) {
        Api api = new Api();
        api.setId(API_ID);
        api.setSourceApiMethod("GET");
        api.setSourceApiUrl("http://127.0.0.1:" + port + path);
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
    }

    @Test
    void callApi_whenUpstreamExceedsResponseTimeout_returnsEmptyWithoutBlockingForever() {
        server.createContext("/slow", exchange -> {
            sleep(5_000);
            respond(exchange, "{\"ok\":true}");
        });
        stubApi("/slow");
        GenericApiService service = serviceWithResponseTimeout(Duration.ofMillis(500));

        long start = System.nanoTime();
        Optional<Map<String, Object>> result = service.callApi(API_ID, null, null, null, false, MAP_TYPE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result).isEmpty();
        assertThat(elapsedMs)
                .as("response timeout should abort the call well before the 5s upstream delay")
                .isLessThan(4_000L);
    }

    @Test
    void callApi_whenUpstreamExceedsResponseTimeout_andThrowError_throwsIllegalState() {
        server.createContext("/slow", exchange -> {
            sleep(5_000);
            respond(exchange, "{\"ok\":true}");
        });
        stubApi("/slow");
        GenericApiService service = serviceWithResponseTimeout(Duration.ofMillis(500));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.callApi(API_ID, null, null, null, true, MAP_TYPE))
                .isInstanceOf(IllegalStateException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(4_000L);
    }

    @Test
    void callApi_whenUpstreamResponds_returnsBody() {
        server.createContext("/fast", exchange -> respond(exchange, "{\"ok\":true}"));
        stubApi("/fast");
        GenericApiService service = serviceWithResponseTimeout(Duration.ofSeconds(5));

        Optional<Map<String, Object>> result = service.callApi(API_ID, null, null, null, false, MAP_TYPE);

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("ok", true);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            exchange.close();
        }
    }
}

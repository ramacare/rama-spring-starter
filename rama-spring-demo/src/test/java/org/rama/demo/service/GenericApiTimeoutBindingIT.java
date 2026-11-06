package org.rama.demo.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.entity.api.Api;
import org.rama.repository.api.ApiRepository;
import org.rama.service.GenericApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code rama.api.*} reaches the {@link GenericApiService} bean and that each value lands
 * on its own setting (starter#66). The upstream answers after 5s, so the call only comes back
 * empty if the 300ms response timeout is in force: the default of 15s, or the 30s connect
 * timeout swapped into its place, would both let the response through.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "rama.api.connect-timeout=30s",
        "rama.api.response-timeout=300ms"
})
class GenericApiTimeoutBindingIT {

    private static final String API_ID = "generic-api-timeout-binding-it";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private GenericApiService genericApiService;

    @Autowired
    private ApiRepository apiRepository;

    private HttpServer server;
    private ExecutorService upstreamExecutor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamExecutor = Executors.newCachedThreadPool();
        server.setExecutor(upstreamExecutor);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        Api api = new Api();
        api.setId(API_ID);
        api.setSourceApiMethod("GET");
        api.setSourceApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        apiRepository.save(api);
    }

    @AfterEach
    void tearDown() {
        apiRepository.deleteById(API_ID);
        server.stop(0);
        upstreamExecutor.shutdownNow();
    }

    @Test
    void responseTimeoutProperty_abortsTheCallOnTheServiceBean() {
        long start = System.nanoTime();
        Optional<Map<String, Object>> result = genericApiService.callApi(API_ID, null, null, null, false, MAP_TYPE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result).isEmpty();
        assertThat(elapsedMs).isLessThan(4_000L);
    }
}

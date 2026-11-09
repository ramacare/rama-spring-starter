package org.rama.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ChannelOption;
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
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

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
    private static final Pattern DURATION_MS = Pattern.compile("durationMs=(\\d+)");
    private static final long HEADER_LOOKUP_DELAY_MS = 1_000L;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiHeaderSetRepository apiHeaderSetRepository;

    private HttpServer server;
    private ExecutorService upstreamExecutor;
    private int port;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(GenericApiService.class);
    private final ListAppender<ILoggingEvent> serviceLogs = new ListAppender<>();
    private Level originalLogLevel;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // Handlers run on their own threads: on the default executor they would run on the
        // dispatcher thread, which server.stop(0) joins, so every test with a slow handler
        // would wait out the full upstream delay in tearDown.
        upstreamExecutor = Executors.newCachedThreadPool();
        server.setExecutor(upstreamExecutor);
        server.start();

        originalLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        serviceLogs.start();
        serviceLogger.addAppender(serviceLogs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(serviceLogs);
        serviceLogger.setLevel(originalLogLevel);
        if (server != null) {
            server.stop(0);
        }
        if (upstreamExecutor != null) {
            upstreamExecutor.shutdownNow();
        }
    }

    private GenericApiService serviceWithResponseTimeout(Duration responseTimeout) {
        return new GenericApiService(apiRepository, apiHeaderSetRepository, WebClient.builder(),
                JsonMapper.builder().build(), Duration.ofSeconds(5), responseTimeout);
    }

    private Api stubApi(String path) {
        Api api = new Api();
        api.setId(API_ID);
        api.setSourceApiMethod("GET");
        api.setSourceApiUrl("http://127.0.0.1:" + port + path);
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
        return api;
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

    /**
     * The latency line exists to pin a slow backend during an incident. A wait for a DB
     * connection in the header-set lookup must not be reported as latency of {@code host}.
     */
    @Test
    void callApi_loggedDurationExcludesHeaderSetLookup() {
        server.createContext("/fast", exchange -> respond(exchange, "{\"ok\":true}"));
        Api api = stubApi("/fast");
        when(apiHeaderSetRepository.findById("slow-header-set")).thenAnswer(invocation -> {
            sleep(HEADER_LOOKUP_DELAY_MS);
            return Optional.empty();
        });
        GenericApiService service = serviceWithResponseTimeout(Duration.ofSeconds(5));
        // Netty's first-use class loading can take over a second; keep it out of the measurement.
        service.callApi(API_ID, null, null, null, false, MAP_TYPE);
        serviceLogs.list.clear();
        api.setApiHeaderSetId("slow-header-set");

        service.callApi(API_ID, null, null, null, false, MAP_TYPE);

        assertThat(loggedDurationMs()).isLessThan(HEADER_LOOKUP_DELAY_MS);
    }

    @Test
    void callApi_requestsAndDecodesGzippedResponses() {
        AtomicReference<String> acceptEncoding = new AtomicReference<>();
        server.createContext("/gzip", exchange -> {
            acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] body = gzip("{\"ok\":true}");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stubApi("/gzip");
        GenericApiService service = serviceWithResponseTimeout(Duration.ofSeconds(5));

        Optional<Map<String, Object>> result = service.callApi(API_ID, null, null, null, false, MAP_TYPE);

        assertThat(acceptEncoding.get()).contains("gzip");
        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("ok", true);
    }

    /**
     * The builder is a shared singleton that {@code PdfService} also builds from, so the
     * timeout connector must land on a clone of it, never on the builder itself.
     */
    @Test
    void constructor_leavesTheSharedBuildersConnectorInPlace() {
        AtomicBoolean sharedConnectorUsed = new AtomicBoolean();
        ClientHttpConnector sharedConnector = (method, uri, requestCallback) -> {
            sharedConnectorUsed.set(true);
            return Mono.error(new IllegalStateException("stop after reaching the connector"));
        };
        WebClient.Builder sharedBuilder = WebClient.builder().clientConnector(sharedConnector);

        new GenericApiService(apiRepository, apiHeaderSetRepository, sharedBuilder,
                JsonMapper.builder().build(), Duration.ofSeconds(5), Duration.ofSeconds(5));
        sharedBuilder.build().get().uri("http://127.0.0.1:" + port + "/")
                .retrieve().toBodilessEntity().onErrorComplete().block();

        assertThat(sharedConnectorUsed).isTrue();
    }

    @Test
    void createHttpClient_appliesEachTimeoutToItsOwnSetting() {
        HttpClient client = GenericApiService.createHttpClient(Duration.ofMillis(1_234), Duration.ofMillis(5_678));

        assertThat((Object) client.configuration().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS))
                .isEqualTo(1_234);
        assertThat(client.configuration().responseTimeout()).isEqualTo(Duration.ofMillis(5_678));
    }

    private long loggedDurationMs() {
        return serviceLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(DURATION_MS::matcher)
                .filter(Matcher::find)
                .mapToLong(matcher -> Long.parseLong(matcher.group(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no latency line logged: " + serviceLogs.list));
    }

    private static byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
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

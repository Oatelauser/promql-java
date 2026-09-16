package io.github.oatelauser.promql.api;

import io.github.oatelauser.promql.Promql;
import io.github.oatelauser.promql.api.response.LabelNamesData;
import io.github.oatelauser.promql.api.response.PrometheusException;
import io.github.oatelauser.promql.api.response.VectorData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkHttpPrometheusClient} 真传输集成：本机回环
 * {@code com.sun.net.httpserver.HttpServer}。覆盖 POST form body、GET 查询串
 * 的 {@code match%5B%5D} 编码、错误 envelope 透传、HTTP 层超时。
 */
class JdkHttpPrometheusClientTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastQueryBody = new AtomicReference<>();
    private static final AtomicReference<String> lastContentType = new AtomicReference<>();
    private static final AtomicReference<String> lastUri = new AtomicReference<>();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/v1/query", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastQueryBody.set(body);
            lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            lastUri.set(ex.getRequestURI().toString());
            if (body.contains("slow_marker")) {
                try {
                    Thread.sleep(7000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    reply(ex, 200, "{}");
                } catch (IOException ignored) {
                    // 客户端已超时断开
                }
                return;
            }
            reply(ex, 200, """
                    {"status":"success","data":{"resultType":"vector","result":[
                      {"metric":{"__name__":"up","job":"prometheus"},"value":[1435341451.781,"1"]}]}}""");
        });

        server.createContext("/api/v1/labels", ex -> {
            lastUri.set(ex.getRequestURI().toString());
            lastAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 200, "{\"status\":\"success\",\"data\":[\"__name__\",\"job\"]}");
        });

        server.createContext("/api/v1/series", ex -> {
            lastUri.set(ex.getRequestURI().toString());
            reply(ex, 503, "{\"status\":\"error\",\"errorType\":\"unavailable\",\"error\":\"service busy\"}");
        });

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void queryPostsFormBody() {
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base);
        VectorData d = (VectorData) client.query(Promql.parse("up"), 1435341451781L, Duration.ofSeconds(30));

        assertEquals("application/x-www-form-urlencoded", lastContentType.get());
        assertEquals("query=up&time=1435341451.781&timeout=30s", lastQueryBody.get());
        assertEquals("/api/v1/query", lastUri.get());
        assertEquals(1, d.samples().size());
        assertEquals("up", d.samples().get(0).metric().get(0).value());
    }

    @Test
    void labelNamesGetEncodesMatchBracket() {
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base);
        LabelNamesData d = client.labelNames(null, null, List.of("up{job=\"prometheus\"}"));

        String uri = lastUri.get();
        assertTrue(uri.startsWith("/api/v1/labels?"), uri);
        assertTrue(uri.contains("match%5B%5D=up%7Bjob%3D%22prometheus%22%7D"), uri);
        assertEquals(List.of("__name__", "job"), d.names());
    }

    @Test
    void errorEnvelopeSurfacesWithTypeAndStatus() {
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base);
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> client.series(null, null, List.of("up")));
        assertEquals(503, e.httpStatus());
        assertEquals("service busy", e.getMessage());
        assertTrue(lastUri.get().contains("match%5B%5D=up"));
    }

    @Test
    void requestDecoratorAddsHeader() {
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base, HttpClient.newHttpClient(),
                Duration.ofSeconds(5), b -> b.header("Authorization", "Bearer tok"));
        client.labelNames(null, null, null);
        assertEquals("Bearer tok", lastAuthorization.get());
    }

    @Test
    void defaultTimeoutAppliesWithoutTimeoutParam() {
        // 无 timeout 参数 → defaultTimeout 生效（注入 300ms）；slow_marker 让服务端睡 7s
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base, Duration.ofMillis(300));
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> client.query(Promql.parse("slow_marker")));
        assertTrue(e.getCause() instanceof HttpTimeoutException, String.valueOf(e.getCause()));
    }

    @Test
    void transportTimeoutWrappedUnchecked() {
        // 标记查询让服务端睡 7s；timeout 参数 100ms + 5s 余量 → HTTP 超时 ~5.1s 必触发
        JdkHttpPrometheusClient client = new JdkHttpPrometheusClient(base + "/");
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> client.query(Promql.parse("slow_marker"), null,
                        Duration.ofMillis(100), VectorData.class));
        assertTrue(e.getCause() instanceof HttpTimeoutException, String.valueOf(e.getCause()));
    }
}

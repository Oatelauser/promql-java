package io.github.oatelauser.promql.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link AbstractPrometheusClient} 的默认实现：JDK 自带
 * {@code java.net.http.HttpClient}（Java 11+，零第三方传输依赖，Q1 决定）。
 *
 * <p>协议映射：GET → 参数进查询串；POST → form body。
 *
 * <p><b>超时</b>：请求携带 {@code timeout} 参数时，HTTP 层超时取该值 + 5 秒
 * 余量——保证服务器的错误 JSON 能送达绑定层归类，而非先在传输层超时报 IO 错；
 * 不携带时取 {@code defaultTimeout}（缺省镜像服务器自身的默认查询超时 2m
 * 再加 5s 余量，避免对僵死服务器无限等待），传 {@code null} 显式关闭。
 * 默认客户端另设 10s 连接超时（连接建立等不到任何有意义的结果）。
 *
 * <p><b>鉴权/定制 header</b>：构造器收 {@code requestDecorator}（如
 * {@code b -> b.header("Authorization", "Bearer " + token)}）；Basic 鉴权
 * 也可注入自配 {@code HttpClient}（{@code Builder.authenticator}）。
 */
public final class JdkHttpPrometheusClient extends AbstractPrometheusClient {

    /**
     * 服务器默认查询超时 2m + 5s 余量（对齐 {@code --query.timeout} 缺省）。
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(125);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final Duration defaultTimeout;
    private final Consumer<HttpRequest.Builder> requestDecorator;

    /**
     * 默认实现：自建 {@link HttpClient}（10s 连接超时）+ 默认请求超时。
     */
    public JdkHttpPrometheusClient(String baseUrl) {
        this(baseUrl, newClient(), DEFAULT_TIMEOUT, b -> {
        });
    }

    /**
     * 注入自配置的 {@link HttpClient}（连接池、代理、authenticator 等由调用方管理）。
     */
    public JdkHttpPrometheusClient(String baseUrl, HttpClient http) {
        this(baseUrl, http, DEFAULT_TIMEOUT, b -> {
        });
    }

    /**
     * @param defaultTimeout 无 {@code timeout} 参数时的 HTTP 层超时；
     *                       {@code null} 显式关闭（不推荐）
     */
    public JdkHttpPrometheusClient(String baseUrl, Duration defaultTimeout) {
        this(baseUrl, newClient(), defaultTimeout, b -> {
        });
    }

    /**
     * 全量注入：自配置客户端 + 默认超时 + 请求装饰器（鉴权 header 等）。
     */
    public JdkHttpPrometheusClient(String baseUrl, HttpClient http, Duration defaultTimeout,
            Consumer<HttpRequest.Builder> requestDecorator) {
        super(baseUrl);
        this.http = Objects.requireNonNull(http, "http");
        this.defaultTimeout = defaultTimeout;
        this.requestDecorator = Objects.requireNonNull(requestDecorator, "requestDecorator");
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    protected RawResponse send(RawRequest request) throws IOException, InterruptedException {
        String form = HttpUtils.formEncode(request.params());
        URI uri = URI.create(baseUrl + request.path()
                + ("GET".equals(request.method()) && !form.isEmpty() ? "?" + form : ""));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if ("POST".equals(request.method())) {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        Duration effective = resolveTimeout(request.paramValue("timeout"));
        if (effective != null) {
            builder.timeout(effective);
        }
        requestDecorator.accept(builder);
        HttpResponse<String> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new RawResponse(resp.statusCode(), resp.body());
    }

    /**
     * {@code timeout} 参数（+5s 余量）优先；否则用默认超时。
     */
    private Duration resolveTimeout(String timeoutParam) {
        if (timeoutParam != null) {
            Duration t = Durations.parse(timeoutParam);
            if (t != null) {
                return t.plusSeconds(5);
            }
        }
        return defaultTimeout;
    }
}

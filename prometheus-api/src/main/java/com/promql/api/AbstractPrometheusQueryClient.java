package com.promql.api;

import com.promql.api.response.MatrixData;
import com.promql.api.response.QueryData;
import com.promql.api.response.ResponseBinder;
import com.promql.api.response.ScalarData;
import com.promql.api.response.StringData;
import com.promql.api.response.VectorData;
import com.promql.ast.Expr;
import com.promql.value.ValueType;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Prometheus HTTP API 客户端抽象基类的<b>父层</b>（纯抽象，Q2=B）：传输核心
 * + 第 1 层查询端点（{@code /api/v1/query}、{@code /api/v1/query_range}）。
 * 请求构造、基于 {@link Expr#type()} 的响应类型推断（Q4=C：静态推断 +
 * 运行时 {@code resultType} 交叉校验）与响应绑定在此层完成；传输完全由
 * 子类实现。查询族端点（series/labels/label values/exemplars）在
 * {@link AbstractPrometheusClient} 子层追加——只要查询端点时继承本类即可。
 *
 * <p>时间参数口径：{@code Long} 为 epoch 毫秒（{@code null} 表示省略该
 * 参数、由服务器取默认）；超时与步长用 {@link Duration}。
 *
 * <p>异常约定（Q5=A）：业务错误（{@code status:"error"}、类型不一致、
 * 畸形响应）抛 {@code PrometheusException}；传输失败（IO 与中断）统一为
 * {@link UncheckedIOException}——中断先恢复线程标志位，cause 为
 * {@link InterruptedIOException}（{@code getCause() instanceof
 * InterruptedIOException} 即识别）。
 */
public abstract class AbstractPrometheusQueryClient {

    /**
     * 服务器基址（如 {@code http://localhost:9090}；尾随 {@code /} 会被剥除）。
     */
    protected final String baseUrl;

    protected AbstractPrometheusQueryClient(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = baseUrl.endsWith("/") && baseUrl.length() > 1
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 唯一传输钩子：把传输无关的 {@link RawRequest} 映射为真实协议调用。
     *
     * <p>约定：GET → 参数编码进查询串；POST → 参数作 form body；
     * 不得吞掉 IO/中断异常（基类负责统一包装上抛）。
     */
    protected abstract RawResponse send(RawRequest request) throws IOException, InterruptedException;

    // ════════ 第 1 层：查询 ════════

    /**
     * 即时查询（服务器当前时间、默认超时）。
     * 响应变体由 {@code expr.type()} 静态推断（Q4=C）。
     */
    public QueryData query(Expr expr) {
        return query(expr, null, null);
    }

    /**
     * 即时查询。
     *
     * @param time    评估时间（epoch 毫秒）；{@code null} 为服务器当前时间
     * @param timeout 查询超时；{@code null} 用服务器默认
     */
    public QueryData query(Expr expr, Long time, Duration timeout) {
        return query(expr, time, timeout, (RequestOptions) null);
    }

    /**
     * 即时查询（带 {@link RequestOptions}：覆盖 HTTP 方法、透传额外参数）。
     * 本端点缺省 POST。
     */
    public QueryData query(Expr expr, Long time, Duration timeout, RequestOptions options) {
        ValueType expected = bindableType(expr);
        List<RawRequest.Param> params = new ArrayList<>();
        params.add(new RawRequest.Param("query", expr.toPromql()));
        addTime(params, "time", time);
        if (timeout != null) {
            params.add(new RawRequest.Param("timeout", Durations.format(timeout)));
        }
        return bind(toRequest("/api/v1/query", params, true, options),
                (body, status) -> ResponseBinder.bindQuery(body, status, expected));
    }

    /**
     * 即时查询（编译期显式类型重载，Q12=A+B）。
     * {@code type} 与表达式静态类型不符时立即抛
     * {@link IllegalArgumentException}（客户端就地失败，不发请求）。
     */
    public <T extends QueryData> T query(Expr expr, Long time, Duration timeout, Class<T> type) {
        return query(expr, time, timeout, type, null);
    }

    /**
     * 即时查询（显式类型 + {@link RequestOptions}；类型校验先于选项处理）。
     */
    public <T extends QueryData> T query(Expr expr, Long time, Duration timeout,
            Class<T> type, RequestOptions options) {
        Objects.requireNonNull(type, "type");
        ValueType expected = bindableType(expr);
        if (!variantOf(expected).equals(type)) {
            throw new IllegalArgumentException("表达式静态类型为 " + expected.documentedType()
                    + "（对应 " + variantOf(expected).getSimpleName() + "），与请求的 "
                    + type.getSimpleName() + " 不符: " + expr.toPromql());
        }
        return type.cast(query(expr, time, timeout, options));
    }

    /**
     * 区间查询（{@code resultType} 恒为 matrix）。
     *
     * @param start   起始时间（epoch 毫秒）
     * @param end     结束时间（epoch 毫秒）
     * @param step    步长（必填）
     * @param timeout 查询超时；{@code null} 用服务器默认
     */
    public MatrixData queryRange(Expr expr, long start, long end, Duration step, Duration timeout) {
        return queryRange(expr, start, end, step, timeout, null);
    }

    /**
     * 区间查询（带 {@link RequestOptions}；本端点缺省 POST）。
     */
    public MatrixData queryRange(Expr expr, long start, long end, Duration step, Duration timeout,
            RequestOptions options) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(step, "step");
        bindableType(expr);
        List<RawRequest.Param> params = new ArrayList<>();
        params.add(new RawRequest.Param("query", expr.toPromql()));
        params.add(new RawRequest.Param("start", seconds(start)));
        params.add(new RawRequest.Param("end", seconds(end)));
        params.add(new RawRequest.Param("step", Durations.format(step)));
        if (timeout != null) {
            params.add(new RawRequest.Param("timeout", Durations.format(timeout)));
        }
        return bind(toRequest("/api/v1/query_range", params, true, options),
                (body, status) -> ResponseBinder.bindQuery(body, status, ValueType.MATRIX),
                MatrixData.class);
    }

    // ════════ 内部（端点构造与传输共用）════════

    /**
     * 发送 + 绑定。自定义端点的扩展点：构造 {@link RawRequest} 后以
     * {@code (body, status) -> …} 形式给出绑定逻辑。
     */
    protected final <T> T bind(RawRequest request, BiFunction<String, Integer, T> binder) {
        RawResponse resp = doSend(request);
        return binder.apply(resp.body(), resp.statusCode());
    }

    /** {@link #bind} 的 {@code QueryData} 变体收窄版。 */
    protected final <T extends QueryData> T bind(RawRequest request, BiFunction<String, Integer,
            QueryData> binder, Class<T> type) {
        return type.cast(bind(request, binder));
    }

    private RawResponse doSend(RawRequest request) {
        try {
            return send(request);
        } catch (IOException e) {
            throw new UncheckedIOException("Prometheus 请求失败（" + request.method() + " " + request.path() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Prometheus 请求被中断（" + request.path() + "）");
            interrupted.initCause(e);
            throw new UncheckedIOException(interrupted);
        }
    }

    /**
     * 表达式类型 → 可绑定的响应类型；NONE（或 null）在客户端就地拒绝。
     */
    private static ValueType bindableType(Expr expr) {
        Objects.requireNonNull(expr, "expr");
        ValueType t = expr.type();
        if (t == null || t == ValueType.NONE) {
            throw new IllegalArgumentException("表达式结果类型为 none，无法推断响应类型: " + expr.toPromql());
        }
        return t;
    }

    /**
     * {@code ValueType} → 对应响应变体类（泛型重载的 fail-fast 依据）。
     */
    private static Class<? extends QueryData> variantOf(ValueType t) {
        return switch (t) {
            case VECTOR -> VectorData.class;
            case MATRIX -> MatrixData.class;
            case SCALAR -> ScalarData.class;
            case STRING -> StringData.class;
            default -> throw new IllegalArgumentException("无法映射的结果类型: " + t);
        };
    }

    /**
     * 标准参数 + 请求选项 → 最终请求：{@code options == null} 时用端点缺省方法
     * （查询族端点均为 POST）；{@link RequestOptions#extraParams()} 追加在标准
     * 参数之后（同名键形成重复键，与 {@code match[]} 多值语义一致）。供两层
     * 端点共用。
     */
    protected static RawRequest toRequest(String path, List<RawRequest.Param> params,
            boolean defaultPost, RequestOptions options) {
        boolean post = options == null ? defaultPost : options.usePost();
        List<RawRequest.Param> all = params;
        if (options != null && !options.extraParams().isEmpty()) {
            all = new ArrayList<>(params);
            all.addAll(options.extraParams());
        }
        return post ? RawRequest.post(path, all) : RawRequest.get(path, all);
    }

    /**
     * 追加可选时间参数（{@code null} 省略）。供两层端点共用。
     */
    protected static void addTime(List<RawRequest.Param> params, String name, Long epochMillis) {
        if (epochMillis != null) {
            params.add(new RawRequest.Param(name, seconds(epochMillis)));
        }
    }

    /**
     * epoch 毫秒 → Prometheus 秒字面量（纯十进制，毫秒精度无损；Go ParseFloat 兼容）。
     */
    protected static String seconds(long epochMillis) {
        long whole = Math.floorDiv(epochMillis, 1000);
        long frac = Math.floorMod(epochMillis, 1000);
        if (frac == 0) {
            return Long.toString(whole);
        }
        String fs = frac < 10 ? "00" + frac : frac < 100 ? "0" + frac : Long.toString(frac);
        return whole + "." + fs;
    }
}

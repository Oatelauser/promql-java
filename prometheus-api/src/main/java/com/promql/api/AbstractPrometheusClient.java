package com.promql.api;

import com.promql.Promql;
import com.promql.api.response.ExemplarsData;
import com.promql.api.response.LabelNamesData;
import com.promql.api.response.LabelValuesData;
import com.promql.api.response.ResponseBinder;
import com.promql.api.response.SeriesData;
import com.promql.labels.LabelMatcher;
import com.promql.parser.PromqlParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 完整 Prometheus HTTP API 客户端抽象基类（<b>子层</b>）：在父层
 * {@link AbstractPrometheusQueryClient}（传输核心 + 第 1 层查询端点
 * {@code query}/{@code queryRange}）之上，追加第 2 层<b>查询族端点</b>：
 * {@code series}、{@code labels}、{@code label/<name>/values}、
 * {@code query_exemplars}。
 *
 * <p>查询族参数顺序统一为「时间参数在前、matcher 过滤在后」；选择器入参
 * （{@code series} 的 {@code match[]}、{@code query_exemplars} 的
 * {@code query}）就地经解析器校验，非法不发请求。
 *
 * <p>默认实现 {@link JdkHttpPrometheusClient}；测试可匿名子类化
 * {@link AbstractPrometheusQueryClient#send} 伪造响应。时间口径与异常约定
 * 见父类 javadoc。
 */
public abstract class AbstractPrometheusClient extends AbstractPrometheusQueryClient {

    protected AbstractPrometheusClient(String baseUrl) {
        super(baseUrl);
    }

    // ════════ 第 2 层：查询族端点 ════════

    /**
     * {@code /api/v1/series}：匹配的标签集序列列表（至少一个 matcher）。
     * 每个序列的标签集独立成组，边界不丢失。
     */
    public SeriesData series(Long start, Long end, List<String> matchers) {
        return series(start, end, matchers, null);
    }

    /**
     * {@code /api/v1/series}（带 {@link RequestOptions}；本端点缺省 GET，
     * 大量 {@code match[]} 时可切 POST 规避 URL 长度限制）。
     */
    public SeriesData series(Long start, Long end, List<String> matchers, RequestOptions options) {
        List<RawRequest.Param> params = new ArrayList<>();
        addRange(params, start, end);
        List<String> ms = requireMatchers(matchers);
        for (int i = 0; i < ms.size(); i++) {
            params.add(new RawRequest.Param("match[]", requireSelector(ms.get(i), i)));
        }
        return bind(toRequest("/api/v1/series", params, false, options), ResponseBinder::bindSeries);
    }

    /**
     * {@code /api/v1/labels}：标签名列表（可选 {@code match[]} 过滤）。
     */
    public LabelNamesData labelNames(Long start, Long end, List<String> matchers) {
        return labelNames(start, end, matchers, null);
    }

    /**
     * {@code /api/v1/labels}（带 {@link RequestOptions}；本端点缺省 GET）。
     */
    public LabelNamesData labelNames(Long start, Long end, List<String> matchers,
            RequestOptions options) {
        List<RawRequest.Param> params = new ArrayList<>();
        addRange(params, start, end);
        addMatchers(params, matchers);
        return bind(toRequest("/api/v1/labels", params, false, options), ResponseBinder::bindLabelNames);
    }

    /**
     * {@code /api/v1/label/<label>/values}：指定标签的取值列表。
     */
    public LabelValuesData labelValues(String label, Long start, Long end, List<String> matchers) {
        return labelValues(label, start, end, matchers, null);
    }

    /**
     * {@code /api/v1/label/<label>/values}（带 {@link RequestOptions}；本端点
     * 缺省 GET）。
     */
    public LabelValuesData labelValues(String label, Long start, Long end, List<String> matchers,
            RequestOptions options) {
        Objects.requireNonNull(label, "label");
        List<RawRequest.Param> params = new ArrayList<>();
        addRange(params, start, end);
        addMatchers(params, matchers);
        String path = "/api/v1/label/" + HttpUtils.encodeSegment(label) + "/values";
        return bind(toRequest(path, params, false, options), ResponseBinder::bindLabelValues);
    }

    /**
     * {@code /api/v1/query_exemplars}：exemplar 查询。
     * {@code query} 须为指标选择器（如 {@code http_requests_total{job="api"}}），
     * 就地经解析器校验，非法不发请求。
     */
    public ExemplarsData queryExemplars(String query, Long start, Long end) {
        return queryExemplars(query, start, end, null);
    }

    /**
     * {@code /api/v1/query_exemplars}（带 {@link RequestOptions}；本端点缺省
     * POST，可切 GET）。
     */
    public ExemplarsData queryExemplars(String query, Long start, Long end, RequestOptions options) {
        Objects.requireNonNull(query, "query");
        requireSelector(query, 0);
        List<RawRequest.Param> params = new ArrayList<>();
        params.add(new RawRequest.Param("query", query));
        addRange(params, start, end);
        return bind(toRequest("/api/v1/query_exemplars", params, true, options),
                ResponseBinder::bindExemplars);
    }

    /**
     * {@code /api/v1/query_exemplars}（类型化重载）：匹配器列表渲染为选择器串。
     */
    public ExemplarsData queryExemplars(List<LabelMatcher> matchers, Long start, Long end) {
        return queryExemplars(matchers, start, end, null);
    }

    /**
     * {@code /api/v1/query_exemplars}（类型化重载 + {@link RequestOptions}）。
     */
    public ExemplarsData queryExemplars(List<LabelMatcher> matchers, Long start, Long end,
            RequestOptions options) {
        return queryExemplars(selectorOf(matchers), start, end, options);
    }

    // ════════ 内部（查询族端点专用）════════

    /**
     * 选择器字符串 fail-fast 校验（就地失败，不发请求）。仅校验不重写——原样发送。
     */
    private static String requireSelector(String selector, int index) {
        Objects.requireNonNull(selector, "selector");
        try {
            Promql.parseMetricSelector(selector);
        } catch (PromqlParseException e) {
            throw new IllegalArgumentException("选择器[" + index + "] 不是合法的指标选择器: "
                    + e.getMessage(), e);
        }
        return selector;
    }

    /**
     * 匹配器列表 → 选择器串：{@code {job="x",instance=~"y.*"}}。
     */
    private static String selectorOf(List<LabelMatcher> matchers) {
        Objects.requireNonNull(matchers, "matchers");
        if (matchers.isEmpty()) {
            throw new IllegalArgumentException("exemplar 查询至少需要一个匹配器");
        }
        StringBuilder sb = new StringBuilder("{");
        for (LabelMatcher m : matchers) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(m.toPromql());
        }
        return sb.append('}').toString();
    }

    private static List<String> requireMatchers(List<String> matchers) {
        Objects.requireNonNull(matchers, "matchers");
        if (matchers.isEmpty()) {
            throw new IllegalArgumentException("series 查询至少需要一个 matcher");
        }
        return matchers;
    }

    private static void addMatchers(List<RawRequest.Param> params, List<String> matchers) {
        if (matchers != null) {
            for (int i = 0; i < matchers.size(); i++) {
                params.add(new RawRequest.Param("match[]", requireSelector(matchers.get(i), i)));
            }
        }
    }

    private static void addRange(List<RawRequest.Param> params, Long start, Long end) {
        addTime(params, "start", start);
        addTime(params, "end", end);
    }
}

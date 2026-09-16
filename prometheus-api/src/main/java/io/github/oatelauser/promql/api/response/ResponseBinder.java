package io.github.oatelauser.promql.api.response;

import com.google.gson.*;
import io.github.oatelauser.promql.labels.Label;
import io.github.oatelauser.promql.labels.Labels;
import io.github.oatelauser.promql.util.GoFloat;
import io.github.oatelauser.promql.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gson {@code JsonElement} 树 → 响应 record 的绑定器（内部 API，不保证兼容）。
 *
 * <p>设计（Q20/Q21 共识）：Gson 只做树解析；全部 Prometheus 特有约定——
 * {@code resultType} 分发、字符串编码浮点（{@code "NaN"/"+Inf"} 经
 * {@link GoFloat}）、native histogram 四元组、标签集排序——都收敛在本类，
 * 模型类零 Gson 依赖、零注解。
 *
 * <p>每个公开入口对应一个端点的响应形状；信封级元数据（{@code warnings}、
 * Prometheus 3.x 的 {@code infos}）随数据一并绑定。{@code httpStatus} 与
 * 原始 body 随异常透出便于排障。入口均为无状态纯函数，线程安全。
 */
public final class ResponseBinder {

    private final int status;
    private final String raw;

    private ResponseBinder(String rawBody, int httpStatus) {
        this.raw = rawBody == null ? "" : rawBody;
        this.status = httpStatus;
    }

    // ════════ 入口（每端点一个）════════

    /**
     * 绑定 {@code /api/v1/query}、{@code /api/v1/query_range} 响应。
     *
     * @param expected 由 {@code Expr.type()} 推断的期望类型；与服务器
     *                 {@code resultType} 交叉校验，不一致抛 INTERNAL
     */
    public static QueryData bindQuery(String rawBody, int httpStatus, ValueType expected) {
        return new ResponseBinder(rawBody, httpStatus).query(expected);
    }

    /**
     * 绑定 {@code /api/v1/series} 响应：每个元素是一组标签集（保留序列边界）。
     */
    public static SeriesData bindSeries(String rawBody, int httpStatus) {
        return new ResponseBinder(rawBody, httpStatus).seriesData();
    }

    /**
     * 绑定 {@code /api/v1/labels} 响应：标签名列表。
     */
    public static LabelNamesData bindLabelNames(String rawBody, int httpStatus) {
        return new ResponseBinder(rawBody, httpStatus).namesData();
    }

    /**
     * 绑定 {@code /api/v1/label/<name>/values} 响应：标签值列表。
     */
    public static LabelValuesData bindLabelValues(String rawBody, int httpStatus) {
        return new ResponseBinder(rawBody, httpStatus).valuesData();
    }

    /**
     * 绑定 {@code /api/v1/query_exemplars} 响应。
     */
    public static ExemplarsData bindExemplars(String rawBody, int httpStatus) {
        return new ResponseBinder(rawBody, httpStatus).exemplars();
    }

    // ════════ 查询响应 ════════

    private QueryData query(ValueType expected) {
        JsonObject root = envelope();
        JsonObject data = requireObject(root, "data");
        String resultTypeStr = requireString(data, "resultType");
        ValueType actual = mapResultType(resultTypeStr);
        if (actual == null) {
            throw malformed("未知 resultType: " + resultTypeStr);
        }
        if (actual != expected) {
            throw malformed("表达式静态推断结果为 " + expected.documentedType()
                    + "，与服务器 resultType \"" + resultTypeStr + "\" 不一致");
        }
        JsonElement result = require(data, "result");
        List<String> warnings = stringArrayField(root, "warnings");
        List<String> infos = stringArrayField(root, "infos");
        return switch (actual) {
            case VECTOR -> new VectorData(vectorSamples(result), warnings, infos);
            case MATRIX -> new MatrixData(matrixSeries(result), warnings, infos);
            case SCALAR -> scalar(result, warnings, infos);
            case STRING -> string(result, warnings, infos);
            default -> throw malformed("不可能的 resultType: " + resultTypeStr);
        };
    }

    private List<VectorSample> vectorSamples(JsonElement e) {
        JsonArray arr = asArray(e, "vector result");
        List<VectorSample> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject o = asObject(el, "vector 样本");
            // Go api.go respondVector：native histogram 样本用 "histogram" 键，浮点样本用 "value"
            JsonElement valueEl = o.has("histogram") ? o.get("histogram") : require(o, "value");
            JsonArray pair = asArray(valueEl, "样本二元组");
            requirePairSize(pair);
            out.add(new VectorSample(
                    labels(o.get("metric")),
                    timestamp(pair.get(0)),
                    sampleValue(pair.get(1))));
        }
        return out;
    }

    private List<MatrixSeries> matrixSeries(JsonElement e) {
        JsonArray arr = asArray(e, "matrix result");
        List<MatrixSeries> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject o = asObject(el, "matrix 序列");
            List<Sample> floats = new ArrayList<>();
            JsonElement values = o.get("values");
            if (values != null && !values.isJsonNull()) {
                for (JsonElement p : asArray(values, "values 点列")) {
                    JsonArray pair = asArray(p, "样本二元组");
                    requirePairSize(pair);
                    floats.add(new Sample(timestamp(pair.get(0)), floatValue(pair.get(1), "样本值")));
                }
            }
            List<HistogramSample> hists = new ArrayList<>();
            JsonElement histograms = o.get("histograms");
            if (histograms != null && !histograms.isJsonNull()) {
                for (JsonElement p : asArray(histograms, "histograms 点列")) {
                    JsonArray pair = asArray(p, "histogram 二元组");
                    requirePairSize(pair);
                    hists.add(new HistogramSample(timestamp(pair.get(0)),
                            histogram(asObject(pair.get(1), "histogram 对象"))));
                }
            }
            out.add(new MatrixSeries(labels(o.get("metric")), floats, hists));
        }
        return out;
    }

    private QueryData scalar(JsonElement e, List<String> warnings, List<String> infos) {
        JsonArray pair = asArray(e, "scalar result");
        requirePairSize(pair);
        return new ScalarData(timestamp(pair.get(0)), sampleValue(pair.get(1)), warnings, infos);
    }

    private QueryData string(JsonElement e, List<String> warnings, List<String> infos) {
        JsonArray pair = asArray(e, "string result");
        requirePairSize(pair);
        return new StringData(timestamp(pair.get(0)), asString(pair.get(1), "string 值"), warnings, infos);
    }

    // ════════ 第 2 层端点 ════════

    private SeriesData seriesData() {
        JsonObject root = envelope();
        List<List<Label>> out = new ArrayList<>();
        for (JsonElement el : dataArray(root)) {
            // 一个数组元素 = 一条序列的标签集，不再拍平
            out.add(labels(el));
        }
        return new SeriesData(out, stringArrayField(root, "warnings"), stringArrayField(root, "infos"));
    }

    private LabelNamesData namesData() {
        JsonObject root = envelope();
        return new LabelNamesData(stringList(dataArray(root)),
                stringArrayField(root, "warnings"), stringArrayField(root, "infos"));
    }

    private LabelValuesData valuesData() {
        JsonObject root = envelope();
        return new LabelValuesData(stringList(dataArray(root)),
                stringArrayField(root, "warnings"), stringArrayField(root, "infos"));
    }

    private List<String> stringList(JsonArray arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            out.add(asString(el, "data 数组元素"));
        }
        return out;
    }

    private ExemplarsData exemplars() {
        JsonObject root = envelope();
        List<ExemplarData> out = new ArrayList<>();
        for (JsonElement el : dataArray(root)) {
            JsonObject o = asObject(el, "exemplar 分组");
            List<Exemplar> exs = new ArrayList<>();
            for (JsonElement p : asArray(require(o, "exemplars"), "exemplars 列表")) {
                JsonObject eo = asObject(p, "exemplar");
                exs.add(new Exemplar(
                        labels(require(eo, "labels")),
                        floatValue(require(eo, "value"), "exemplar 值"),
                        timestamp(require(eo, "timestamp"))));
            }
            out.add(new ExemplarData(labels(require(o, "seriesLabels")), exs));
        }
        return new ExemplarsData(out, stringArrayField(root, "warnings"), stringArrayField(root, "infos"));
    }

    // ════════ envelope 与叶子规则 ════════

    /**
     * 校验 {@code status}；{@code error} 时抛携带 {@code errorType} 的异常。
     */
    private JsonObject envelope() {
        JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (JsonParseException | IllegalStateException ex) {
            throw malformed("响应不是合法 JSON: " + ex.getMessage());
        }
        JsonObject o = asObject(root, "响应根");
        String statusStr = requireString(o, "status");
        if ("error".equals(statusStr)) {
            ErrorType type = o.has("errorType")
                    ? ErrorType.from(requireString(o, "errorType"))
                    : ErrorType.INTERNAL;
            String message = o.has("error") && !o.get("error").isJsonNull()
                    ? asString(o.get("error"), "error") : "";
            throw new PrometheusException(type, this.status, message, raw);
        }
        if (!"success".equals(statusStr)) {
            throw malformed("未知 status: " + statusStr);
        }
        return o;
    }

    private JsonArray dataArray(JsonObject root) {
        return asArray(require(root, "data"), "data");
    }

    /**
     * 标签集 JSON 对象 → 按 name 排序的 {@code List<Label>}（与 AST 侧同一不变式）。
     */
    private List<Label> labels(JsonElement e) {
        JsonObject o = asObject(e, "标签集");
        List<Label> out = new ArrayList<>(o.size());
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            JsonElement v = en.getValue();
            out.add(new Label(en.getKey(), v.isJsonNull() ? "" : v.getAsString()));
        }
        return Labels.newLabels(out);
    }

    /**
     * 样本值：JSON 对象 → histogram；字符串/数字 → 浮点（经 {@link GoFloat}）。
     */
    private SampleValue sampleValue(JsonElement e) {
        if (e != null && e.isJsonObject()) {
            return new HistogramValue(histogram(e.getAsJsonObject()));
        }
        return new FloatValue(floatValue(e, "样本值"));
    }

    private Histogram histogram(JsonObject o) {
        long count = (long) floatValue(require(o, "count"), "histogram.count");
        double sum = floatValue(require(o, "sum"), "histogram.sum");
        List<Bucket> buckets = new ArrayList<>();
        JsonElement bs = o.get("buckets");
        if (bs != null && !bs.isJsonNull()) {
            for (JsonElement b : asArray(bs, "buckets")) {
                JsonArray t = asArray(b, "bucket 四元组");
                if (t.size() != 4) {
                    throw malformed("bucket 四元组长度应为 4，实得 " + t.size());
                }
                buckets.add(new Bucket(
                        t.get(0).getAsInt(),
                        floatValue(t.get(1), "bucket 下界"),
                        floatValue(t.get(2), "bucket 上界"),
                        (long) floatValue(t.get(3), "bucket 计数")));
            }
        }
        return new Histogram(count, sum, buckets);
    }

    /**
     * double 秒 → long 毫秒（四舍五入防浮点尾巴）。
     */
    private long timestamp(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw malformed("时间戳应为数字，实得: " + e);
        }
        return Math.round(e.getAsDouble() * 1000.0);
    }

    /**
     * 字符串编码浮点（{@code "NaN"/"+Inf"/"1.5"}）或 JSON 数字 → double，经 {@link GoFloat}。
     */
    private double floatValue(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive()) {
            throw malformed(what + " 应为字符串或数字，实得: " + e);
        }
        try {
            if (e.getAsJsonPrimitive().isNumber()) {
                return e.getAsDouble();
            }
            return GoFloat.parseFloat(e.getAsString());
        } catch (RuntimeException ex) {
            throw malformed(what + " 不是合法的浮点文本: " + e + "（" + ex.getMessage() + "）");
        }
    }

    /**
     * 信封级字符串数组字段（{@code warnings}/{@code infos}）：缺省/空 → 空列表。
     */
    private List<String> stringArrayField(JsonObject root, String field) {
        JsonElement el = root.get(field);
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : asArray(el, field)) {
            out.add(asString(item, field));
        }
        return out;
    }

    // ════════ 结构校验助手 ════════

    private static ValueType mapResultType(String s) {
        return switch (s) {
            case "vector" -> ValueType.VECTOR;
            case "matrix" -> ValueType.MATRIX;
            case "scalar" -> ValueType.SCALAR;
            case "string" -> ValueType.STRING;
            default -> null;
        };
    }

    private JsonElement require(JsonObject parent, String field) {
        JsonElement e = parent.get(field);
        if (e == null || e.isJsonNull()) {
            throw malformed("缺少字段 " + field);
        }
        return e;
    }

    private JsonObject requireObject(JsonObject parent, String field) {
        return asObject(require(parent, field), field);
    }

    private String requireString(JsonObject parent, String field) {
        return asString(require(parent, field), field);
    }

    private JsonObject asObject(JsonElement e, String what) {
        if (e == null || !e.isJsonObject()) {
            throw malformed(what + " 应为 JSON 对象，实得: " + e);
        }
        return e.getAsJsonObject();
    }

    private JsonArray asArray(JsonElement e, String what) {
        if (e == null || !e.isJsonArray()) {
            throw malformed(what + " 应为 JSON 数组，实得: " + e);
        }
        return e.getAsJsonArray();
    }

    private String asString(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw malformed(what + " 应为字符串，实得: " + e);
        }
        return e.getAsString();
    }

    private void requirePairSize(JsonArray pair) {
        if (pair.size() != 2) {
            throw malformed("[时间戳, 值] 二元组长度应为 2，实得 " + pair.size());
        }
    }

    private PrometheusException malformed(String message) {
        return new PrometheusException(ErrorType.INTERNAL, status, "畸形响应: " + message, raw);
    }
}

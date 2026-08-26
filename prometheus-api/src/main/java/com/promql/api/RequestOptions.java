package com.promql.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 端点请求选项：HTTP 方法选择（GET 查询串 vs POST form）+ 额外参数透传。
 *
 * <p>Prometheus 查询族端点（query/query_range/series/labels/label values/
 * query_exemplars）对 GET 与 POST（{@code application/x-www-form-urlencoded}）
 * 等价支持——长表达式或大量 {@code match[]} 时用 POST 规避 URL 长度限制。
 * 各端点的<b>缺省</b>方法维持本库既有行为（query/query_range/query_exemplars
 * 为 POST，series/labels/label values 为 GET），传本对象可逐请求覆盖。
 *
 * <p>{@link #extraParams()} 追加在标准参数<b>之后</b>：同名键不合并、形成
 * 重复键（与 {@code match[]} 的多值语义一致）；未知键原样透传——服务器新增
 * 参数无需等本库升级。
 *
 * <p>不可变；{@code null} 选项在所有端点上等价于该端点的缺省行为。
 */
public record RequestOptions(boolean usePost, List<RawRequest.Param> extraParams) {

    /** 全部端点通用缺省（GET、无额外参数）。 */
    public static final RequestOptions DEFAULT = new RequestOptions(false, List.of());

    public RequestOptions {
        extraParams = extraParams == null ? List.of() : List.copyOf(extraParams);
    }

    /** GET + 额外参数。 */
    public static RequestOptions get(List<RawRequest.Param> extraParams) {
        return new RequestOptions(false, extraParams);
    }

    /** GET、无额外参数。 */
    public static RequestOptions get() {
        return DEFAULT;
    }

    /** POST + 额外参数。 */
    public static RequestOptions post(List<RawRequest.Param> extraParams) {
        return new RequestOptions(true, extraParams);
    }

    /** POST、无额外参数。 */
    public static RequestOptions post() {
        return new RequestOptions(true, List.of());
    }

    /** 便捷构造：GET + 单个透传参数。 */
    public static RequestOptions withParam(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return new RequestOptions(false, List.of(new RawRequest.Param(name, value)));
    }

    /** 追加透传参数的副本（本对象不变）。 */
    public RequestOptions withExtra(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        List<RawRequest.Param> merged = new ArrayList<>(extraParams);
        merged.add(new RawRequest.Param(name, value));
        return new RequestOptions(usePost, merged);
    }
}

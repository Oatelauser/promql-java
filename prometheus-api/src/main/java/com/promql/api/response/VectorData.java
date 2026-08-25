package com.promql.api.response;

import java.util.List;

/**
 * 即时向量结果（{@code resultType:"vector"}）。
 *
 * <p>对应 Go：{@code promql.Vector}（[]Sample）。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）：
 * 警告与提示消息（如 PromQL annotation），非数据本身。
 */
public record VectorData(List<VectorSample> samples, List<String> warnings, List<String> infos) implements QueryData {

    public VectorData {
        samples = samples == null ? List.of() : List.copyOf(samples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

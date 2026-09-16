package io.github.oatelauser.promql.api.response;

import java.util.List;

/**
 * 区间向量结果（{@code resultType:"matrix"}）。
 *
 * <p>对应 Go：{@code promql.Matrix}（[]Series）。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）：
 * 警告与提示消息（如 PromQL annotation），非数据本身。
 */
public record MatrixData(List<MatrixSeries> series, List<String> warnings, List<String> infos) implements QueryData {

    public MatrixData {
        series = series == null ? List.of() : List.copyOf(series);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

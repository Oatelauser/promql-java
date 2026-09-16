package io.github.oatelauser.promql.api.response;

import io.github.oatelauser.promql.labels.Label;

import java.util.List;

/**
 * {@code /api/v1/series} 响应：匹配的标签集列表。
 *
 * <p>每个元素是一组标签集（一条序列的身份），组内按 name 排序
 * （{@code Labels.newLabels} 不变式），组间保持服务器返回顺序——
 * 序列边界不丢失。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）。
 */
public record SeriesData(List<List<Label>> series, List<String> warnings, List<String> infos) {

    public SeriesData {
        series = series == null ? List.of() : List.copyOf(series.stream()
                .map(s -> s == null ? List.<Label>of() : List.copyOf(s)).toList());
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

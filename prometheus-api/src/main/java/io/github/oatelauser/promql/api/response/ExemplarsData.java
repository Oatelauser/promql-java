package io.github.oatelauser.promql.api.response;

import java.util.List;

/**
 * {@code /api/v1/query_exemplars} 响应：按序列分组的 exemplar 列表。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）。
 */
public record ExemplarsData(List<ExemplarData> groups, List<String> warnings, List<String> infos) {

    public ExemplarsData {
        groups = groups == null ? List.of() : List.copyOf(groups);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

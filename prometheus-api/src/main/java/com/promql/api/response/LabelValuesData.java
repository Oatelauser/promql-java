package com.promql.api.response;

import java.util.List;

/**
 * {@code /api/v1/label/<name>/values} 响应：指定标签的取值列表。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）。
 */
public record LabelValuesData(List<String> values, List<String> warnings, List<String> infos) {

    public LabelValuesData {
        values = values == null ? List.of() : List.copyOf(values);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

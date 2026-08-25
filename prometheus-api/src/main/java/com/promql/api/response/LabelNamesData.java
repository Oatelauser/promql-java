package com.promql.api.response;

import java.util.List;

/**
 * {@code /api/v1/labels} 响应：标签名列表。
 *
 * <p>{@code warnings}/{@code infos} 是信封级元数据（与 {@code data} 平级）。
 */
public record LabelNamesData(List<String> names, List<String> warnings, List<String> infos) {

    public LabelNamesData {
        names = names == null ? List.of() : List.copyOf(names);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }
}

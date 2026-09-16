package io.github.oatelauser.promql.api.response;

import io.github.oatelauser.promql.labels.Label;

import java.util.List;

/**
 * exemplar 查询结果分组（{@code /api/v1/query_exemplars}）：
 * {@code {"seriesLabels":{...},"exemplars":[...]}}。
 */
public record ExemplarData(List<Label> seriesLabels, List<Exemplar> exemplars) {

    public ExemplarData {
        seriesLabels = seriesLabels == null ? List.of() : List.copyOf(seriesLabels);
        exemplars = exemplars == null ? List.of() : List.copyOf(exemplars);
    }
}

package com.promql.api.response;

import com.promql.labels.Label;

import java.util.List;

/**
 * matrix 结果中的单条序列：浮点点列与 native histogram 点列<b>分列</b>存储。
 *
 * <p>对应 Go：{@code promql.Series} 的 Floats/Histograms 两个字段（JSON 中
 * 分别为 {@code values} 与 {@code histograms}，一条序列可能同时含两者）。
 */
public record MatrixSeries(List<Label> metric, List<Sample> floats, List<HistogramSample> histograms) {

    public MatrixSeries {
        metric = metric == null ? List.of() : List.copyOf(metric);
        floats = floats == null ? List.of() : List.copyOf(floats);
        histograms = histograms == null ? List.of() : List.copyOf(histograms);
    }
}

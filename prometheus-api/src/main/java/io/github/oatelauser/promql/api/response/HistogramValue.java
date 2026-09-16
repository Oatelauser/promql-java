package io.github.oatelauser.promql.api.response;

/**
 * native histogram 样本值（Prometheus 2.40+，Q9=完整支持）。
 */
public record HistogramValue(Histogram histogram) implements SampleValue {
}

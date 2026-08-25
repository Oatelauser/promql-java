package com.promql.api.response;

import com.promql.labels.Label;

import java.time.Instant;
import java.util.List;

/**
 * vector 结果中的单条样本：{@code {"metric":{...},"value":[ts,"v"]}}。
 *
 * <p>对应 Go：{@code promql.Sample}（Metric/T/F/H）。标签集按 name 排序，
 * 与 AST 侧 {@code List<Label>} 保持同一不变式。
 */
public record VectorSample(List<Label> metric, long timestamp, SampleValue value) {

    public VectorSample {
        metric = metric == null ? List.of() : List.copyOf(metric);
    }

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

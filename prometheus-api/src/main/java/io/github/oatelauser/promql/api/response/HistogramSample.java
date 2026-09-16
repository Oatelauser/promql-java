package io.github.oatelauser.promql.api.response;

import java.time.Instant;

/**
 * native histogram 样本点：{@code [ts, {count,sum,buckets}]}（matrix 序列的元素）。
 *
 * <p>对应 Go：{@code promql.HPoint}。
 */
public record HistogramSample(long timestamp, Histogram histogram) {

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

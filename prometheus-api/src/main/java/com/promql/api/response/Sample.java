package com.promql.api.response;

import java.time.Instant;

/**
 * 浮点样本点：{@code [ts, "v"]} 二元组（matrix 序列的元素）。
 *
 * <p>对应 Go：{@code promql.FPoint}。
 */
public record Sample(long timestamp, double value) {

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

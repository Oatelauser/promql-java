package com.promql.api.response;

import com.promql.labels.Label;

import java.time.Instant;
import java.util.List;

/**
 * 单条 exemplar：{@code {"labels":{...},"value":"1","timestamp":1637804423.153}}。
 */
public record Exemplar(List<Label> labels, double value, long timestamp) {

    public Exemplar {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

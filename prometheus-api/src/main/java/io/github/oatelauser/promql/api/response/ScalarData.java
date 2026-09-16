package io.github.oatelauser.promql.api.response;

import java.time.Instant;
import java.util.List;

/**
 * 标量结果（{@code resultType:"scalar"}），线格式为 {@code [时间戳, "值"]}。
 *
 * <p>对应 Go：{@code promql.Scalar}。
 */
public record ScalarData(long timestamp, SampleValue value, List<String> warnings, List<String> infos) implements QueryData {

    public ScalarData {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

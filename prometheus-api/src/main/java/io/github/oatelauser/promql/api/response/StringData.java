package io.github.oatelauser.promql.api.response;

import java.time.Instant;
import java.util.List;

/**
 * 字符串结果（{@code resultType:"string"}），线格式为 {@code [时间戳, "值"]}。
 *
 * <p>对应 Go：{@code promql.String}。
 */
public record StringData(long timestamp, String value, List<String> warnings, List<String> infos) implements QueryData {

    public StringData {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        infos = infos == null ? List.of() : List.copyOf(infos);
    }

    /** 毫秒时间戳 → {@link Instant}。 */
    public Instant toInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}

package io.github.oatelauser.promql.api.response;

import java.util.List;

/**
 * native histogram 线格式：
 * {@code {"count":"10","sum":"0.7","buckets":[[方案,"下界","上界","计数"],...]}}。
 *
 * <p>对应 Go：{@code model.Histogram} 的 JSON 序列化形态（正负侧 span
 * 展开为绝对 bucket 列表）。各数值字段在线格式中均为字符串编码的浮点。
 */
public record Histogram(long count, double sum, List<Bucket> buckets) {

    public Histogram {
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }
}

package com.promql.labels;

/**
 * 单个标签键值对（name + value）。
 *
 * <p>对应 Go：{@code model/labels.Label}。仅由 {@code ParseMetric} 路径
 * 使用（Q11 保留 ParseMetric）；PromQL 表达式里的匹配用
 * {@link LabelMatcher}。
 */
public record Label(String name, String value) {
}

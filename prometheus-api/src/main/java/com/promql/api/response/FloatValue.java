package com.promql.api.response;

/**
 * 浮点样本值；解析经 {@code GoFloat}（Go strconv 语义，含 Inf/NaN 文本）。
 */
public record FloatValue(double value) implements SampleValue {
}

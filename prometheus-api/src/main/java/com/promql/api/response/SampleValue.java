package com.promql.api.response;

/**
 * 样本值的两种形态：普通浮点（线格式为字符串，{@code "NaN"/"+Inf"/"-Inf"/"1.5"}）
 * 或 native histogram（线格式为对象）。
 *
 * <p>sealed 二选一（Q7/Q9 决定），调用方按变体分别处理：
 * <pre>{@code
 * if (v instanceof FloatValue(double f)) { ... }
 * }</pre>
 */
public sealed interface SampleValue permits FloatValue, HistogramValue {
}

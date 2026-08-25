package com.promql.api;

/**
 * 传输无关的原始响应：HTTP 状态码 + 响应 body 文本。
 *
 * <p>状态码是区分 Prometheus 业务错误（{@code status:"error"} + {@code errorType}）
 * 与传输层故障的必要信息，必须透传给绑定层。
 */
public record RawResponse(int statusCode, String body) {
}

package com.promql.api;

import java.util.List;

/**
 * 传输无关的请求描述：由 {@link AbstractPrometheusClient} 构造，交给
 * {@link AbstractPrometheusClient#send(RawRequest)} 的具体实现映射为真实协议。
 *
 * <p>协议约定：{@code GET} 请求把 {@link #params()} 编码进 URL 查询串；
 * {@code POST} 请求编码为 {@code application/x-www-form-urlencoded} body
 * （Prometheus 查询族端点支持 POST，规避长表达式的 URL 长度限制）。
 *
 * <p>参数为有序多值列表（{@code match[]} 等重复键需要），不可变。
 */
public record RawRequest(String method, String path, List<Param> params) {

    /**
     * 单个请求参数（键值对，允许重复键）。
     */
    public record Param(String name, String value) {
    }

    public RawRequest {
        params = params == null ? List.of() : List.copyOf(params);
    }

    /**
     * 构造 GET 请求。
     */
    public static RawRequest get(String path, List<Param> params) {
        return new RawRequest("GET", path, params);
    }

    /**
     * 构造 POST（form 编码）请求。
     */
    public static RawRequest post(String path, List<Param> params) {
        return new RawRequest("POST", path, params);
    }

    /**
     * 取首个同名参数的值；传输实现可借此读取 {@code timeout} 等元信息，无则 {@code null}。
     */
    public String paramValue(String name) {
        for (Param p : params) {
            if (p.name().equals(name)) {
                return p.value();
            }
        }
        return null;
    }
}

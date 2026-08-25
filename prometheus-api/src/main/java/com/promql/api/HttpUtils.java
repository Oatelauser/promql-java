package com.promql.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 表单/查询串编码（内部工具）。{@code URLEncoder} 语义（空格 → {@code +}）
 * 对 form body 与 URL 查询串均合法（Go {@code net/url} 的双语义兼容）。
 */
final class HttpUtils {

    private HttpUtils() {
    }

    /**
     * {@code name=value&name2=value2}，保持参数顺序与重复键。
     */
    static String formEncode(List<RawRequest.Param> params) {
        StringBuilder sb = new StringBuilder();
        for (RawRequest.Param p : params) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(p.name())).append('=').append(encode(p.value()));
        }
        return sb.toString();
    }

    /**
     * URL 路径段编码（如 label 名）。
     */
    static String encodeSegment(String s) {
        return encode(s);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

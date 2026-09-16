package io.github.oatelauser.promql.api.response;

/**
 * Prometheus API 的错误类别，对应响应 {@code errorType} 字段的字符串字面量。
 *
 * <p>对应 Go：{@code web/api/v1/api.go} 各处返回的 errorType 常量。
 */
public enum ErrorType {
    /** 请求参数/表达式非法（HTTP 400/422）。 */
    BAD_DATA("bad_data"),
    /** 查询超时（HTTP 503）。 */
    TIMEOUT("timeout"),
    /** 请求被取消（HTTP 500）。 */
    CANCELED("canceled"),
    /** 查询执行失败（HTTP 422/500）。 */
    EXECUTION("execution"),
    /** 服务不可用/忙（HTTP 503）。 */
    UNAVAILABLE("unavailable"),
    /** 服务器内部错误；亦用于本客户端无法归类的情况（含畸形响应）。 */
    INTERNAL("internal");

    private final String symbol;

    ErrorType(String symbol) {
        this.symbol = symbol;
    }

    /** Go 的字符串字面量形式。 */
    public String symbol() {
        return symbol;
    }

    /**
     * 由 {@code errorType} 字符串解析；未知字面量归 {@link #INTERNAL}
     * （服务器新增错误类别时不至于解析失败）。
     */
    public static ErrorType from(String symbol) {
        if (symbol == null) {
            return INTERNAL;
        }
        for (ErrorType t : values()) {
            if (t.symbol.equals(symbol)) {
                return t;
            }
        }
        // 宽容别名（英式拼写）
        if ("cancelled".equals(symbol)) {
            return CANCELED;
        }
        return INTERNAL;
    }
}

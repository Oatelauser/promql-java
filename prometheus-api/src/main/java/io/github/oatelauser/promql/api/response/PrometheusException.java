package io.github.oatelauser.promql.api.response;

/**
 * Prometheus API 业务错误：{@code status:"error"} 响应、静态推断类型与
 * {@code resultType} 不一致、或响应结构畸形。
 *
 * <p>非受检（Q5 决定）；传输层异常不归此类——{@code IOException} 包装为
 * {@link java.io.UncheckedIOException} 上抛，中断异常恢复标志位后包装。
 */
public final class PrometheusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorType errorType;
    private final int httpStatus;
    private final String rawBody;

    public PrometheusException(ErrorType errorType, int httpStatus, String message, String rawBody) {
        super(message);
        this.errorType = errorType == null ? ErrorType.INTERNAL : errorType;
        this.httpStatus = httpStatus;
        this.rawBody = rawBody;
    }

    /** 错误类别。 */
    public ErrorType errorType() {
        return errorType;
    }

    /** HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 原始响应 body，便于排障；畸形响应时即为问题文本本身。 */
    public String rawBody() {
        return rawBody;
    }
}

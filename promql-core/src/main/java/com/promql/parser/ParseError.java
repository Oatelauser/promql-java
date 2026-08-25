package com.promql.parser;

import com.promql.posrange.PositionRange;

/**
 * 单个解析错误，对应 Go {@code parser/parse.go} 的 {@code ParseErr}。
 *
 * <p>渲染格式与 Go 完全一致：
 * {@code "%s: parse error: %s"}（位置为 {@code 行:列}）。
 */
public record ParseError(
        /*错误定位。 */
        PositionRange positionRange,
        /* 错误消息（英文，与 Go 快照逐字一致，Q12）。 */
        String message,
        /* 被解析的完整查询串。 */
        String query,
        /* 附加行偏移（Go 仅在单元测试中使用）。 */
        int lineOffset) {

    /**
     * 对应 Go {@code ParseErr.Error()}。
     */
    public String error() {
        return String.format("%s: parse error: %s",
                positionRange.startPosInput(query, lineOffset), message);
    }

    @Override
    public String toString() {
        return error();
    }
}

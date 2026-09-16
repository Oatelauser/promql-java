package io.github.oatelauser.promql.posrange;

/**
 * 源文本中的位置区间（零索引，半开区间约定同 Go：{@code [Start, End)}）。
 *
 * <p>对应 Go：{@code promql/parser/posrange/posrange.go} 的
 * {@code PositionRange}。负数表示未定义位置。
 *
 * <p>本类型自身参与自然相等（它只用于错误定位与工具链，见 CONTEXT.md
 * “Position range”）；AST 节点的 equals/hashCode 会忽略位置字段，那是
 * 节点层的约定（Q9），与本类型无关。
 */
public record PositionRange(int start, int end) {

    /**
     * 未定义位置区间的常用哨兵值，对应 Go 中零值的用法。
     */
    public static PositionRange undefined() {
        return new PositionRange(-1, -1);
    }

    /**
     * 借助查询字符串把起始位置渲染为 "line:col" 形式。
     *
     * <p>对应 Go {@code PositionRange.StartPosInput}。查询为空返回
     * "unknown position"；位置越界返回 "invalid position"。
     *
     * @param query      被解析的完整查询字符串
     * @param lineOffset 附加行偏移（Go 仅在单元测试中使用）
     */
    public String startPosInput(String query, int lineOffset) {
        if (query == null || query.isEmpty()) {
            return "unknown position";
        }
        int pos = start;
        if (pos < 0 || pos > query.length()) {
            return "invalid position";
        }

        int lastLineBreak = -1;
        int line = lineOffset + 1;
        for (int i = 0; i < pos; i++) {
            if (query.charAt(i) == '\n') {
                lastLineBreak = i;
                line++;
            }
        }
        int col = pos - lastLineBreak;
        return line + ":" + col;
    }
}

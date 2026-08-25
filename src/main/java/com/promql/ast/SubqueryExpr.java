package com.promql.ast;

import com.promql.posrange.PositionRange;
import com.promql.value.ValueType;

import java.util.Objects;

/**
 * 子查询：{@code foo[5m:1m] @ 123 offset 3m}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *SubqueryExpr}。
 * {@code range/step/originalOffset} 为纳秒；对应表达式形式存在时
 * {@code rangeExpr/stepExpr/originalOffsetExpr} 非空（此时数值字段为 0）。
 *
 * <p><b>裁剪字段（ADR-0003，快照核实）</b>：Go 的 {@code Offset} 从不被
 * 解析器写入（addOffset 只写 OriginalOffset，Offset 由引擎运行期设置），
 * 按语法纯度原则不保留。
 */
public record SubqueryExpr(
        Expr expr,
        long range,
        DurationExpr rangeExpr,
        long originalOffset,
        DurationExpr originalOffsetExpr,
        /** @ 修饰符的毫秒时间戳；@ start()/@ end() 用 startOrEnd，本字段为 null。 */
        Long timestamp,
        StartOrEnd startOrEnd,
        long step,
        DurationExpr stepExpr,
        int endPos) implements Expr {

    public SubqueryExpr {
        Objects.requireNonNull(expr, "expr 不能为空");
        Objects.requireNonNull(startOrEnd, "startOrEnd 不能为空（无修饰符时用 NONE）");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static SubqueryExpr of(Expr expr, long range, DurationExpr rangeExpr,
            long originalOffset, DurationExpr originalOffsetExpr,
            Long timestamp, StartOrEnd startOrEnd,
            long step, DurationExpr stepExpr, int endPos) {
        return new SubqueryExpr(expr, range, rangeExpr, originalOffset, originalOffsetExpr,
                timestamp, startOrEnd, step, stepExpr, endPos);
    }

    @Override
    public ValueType type() {
        return ValueType.MATRIX;
    }

    @Override
    public PositionRange positionRange() {
        return new PositionRange(expr.positionRange().start(), endPos);
    }

    /**
     * 位置字段（endPos）不参与相等性（Q9）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubqueryExpr that)) {
            return false;
        }
        return range == that.range && originalOffset == that.originalOffset
                && step == that.step && startOrEnd == that.startOrEnd
                && Objects.equals(expr, that.expr) && Objects.equals(rangeExpr, that.rangeExpr)
                && Objects.equals(originalOffsetExpr, that.originalOffsetExpr)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(stepExpr, that.stepExpr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expr, range, rangeExpr, originalOffset, originalOffsetExpr,
                timestamp, startOrEnd, step, stepExpr);
    }
}

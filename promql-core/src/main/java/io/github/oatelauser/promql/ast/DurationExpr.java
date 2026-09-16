package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * 时长表达式（实验语法，受 ExperimentalDurationExpr 门控）：
 * {@code [26m+4m]}、{@code foo offset -(2^2)}、{@code [step()]}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *DurationExpr}。lhs/rhs 可空：
 * <ul>
 *   <li>一元负号：{@code lhs == null}，op 为 SUB，rhs 为操作数；</li>
 *   <li>一元正号包装（wrapParenDurationExpr）：{@code lhs == null}、op 为
 *       ADD、rhs 为裸时长——使 {@code (5)} 之类的带括号裸数字能往返；</li>
 *   <li>step()/range()：两侧皆空，仅 startPos/endPos。</li>
 * </ul>
 * 位置区间的四种分支与 Go 的 {@code DurationExpr.PositionRange()} 逐行一致。
 */
public record DurationExpr(
        DurationOp op,
        Expr lhs,
        Expr rhs,
        boolean wrapped,
        int startPos,
        int endPos) implements Expr {

    public DurationExpr {
        Objects.requireNonNull(op, "op 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static DurationExpr of(DurationOp op, Expr lhs, Expr rhs,
            boolean wrapped, int startPos, int endPos) {
        return new DurationExpr(op, lhs, rhs, wrapped, startPos, endPos);
    }

    @Override
    public ValueType type() {
        return ValueType.SCALAR;
    }

    @Override
    public PositionRange positionRange() {
        if (rhs == null && lhs == null) {
            return new PositionRange(startPos, endPos);
        }
        if (rhs == null) {
            return new PositionRange(startPos, lhs.positionRange().end());
        }
        if (lhs == null) {
            return new PositionRange(startPos, rhs.positionRange().end());
        }
        return Node.mergeRanges(lhs, rhs);
    }

    /**
     * 位置字段（startPos/endPos）不参与相等性（Q9）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DurationExpr that)) {
            return false;
        }
        return wrapped == that.wrapped && op == that.op
                && Objects.equals(lhs, that.lhs) && Objects.equals(rhs, that.rhs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, lhs, rhs, wrapped);
    }
}

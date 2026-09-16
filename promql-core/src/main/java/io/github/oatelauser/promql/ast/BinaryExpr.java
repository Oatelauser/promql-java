package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * 二元表达式：{@code a + b}、{@code a == bool on(x) group_left b}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *BinaryExpr}。位置区间由
 * LHS/RHS 合并计算（不落字段）；{@code vectorMatching} 在两侧不全为向量
 * 且无匹配子句时为 null（对应 Go 的 nil 指针）。
 */
public record BinaryExpr(
        BinaryOp op,
        Expr lhs,
        Expr rhs,
        VectorMatching vectorMatching,
        boolean returnBool) implements Expr {

    public BinaryExpr {
        Objects.requireNonNull(op, "op 不能为空");
        Objects.requireNonNull(lhs, "lhs 不能为空");
        Objects.requireNonNull(rhs, "rhs 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static BinaryExpr of(BinaryOp op, Expr lhs, Expr rhs,
            VectorMatching vectorMatching, boolean returnBool) {
        return new BinaryExpr(op, lhs, rhs, vectorMatching, returnBool);
    }

    @Override
    public ValueType type() {
        if (lhs.type() == ValueType.SCALAR && rhs.type() == ValueType.SCALAR) {
            return ValueType.SCALAR;
        }
        return ValueType.VECTOR;
    }

    @Override
    public PositionRange positionRange() {
        return Node.mergeRanges(lhs, rhs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BinaryExpr that)) {
            return false;
        }
        return returnBool == that.returnBool && op == that.op
                && Objects.equals(lhs, that.lhs) && Objects.equals(rhs, that.rhs)
                && Objects.equals(vectorMatching, that.vectorMatching);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, lhs, rhs, vectorMatching, returnBool);
    }
}

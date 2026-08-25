package com.promql.ast;

import com.promql.posrange.PositionRange;
import com.promql.value.ValueType;

import java.util.Objects;

/**
 * 一元表达式：{@code -x}、{@code +x}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *UnaryExpr}。注意解析器会把
 * {@code -5} 折叠进 NumberLiteral（负值），UnaryExpr 只承载非数字场景。
 */
public record UnaryExpr(
        UnaryOp op,
        Expr expr,
        int startPos) implements Expr {

    public UnaryExpr {
        Objects.requireNonNull(op, "op 不能为空");
        Objects.requireNonNull(expr, "expr 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static UnaryExpr of(UnaryOp op, Expr expr, int startPos) {
        return new UnaryExpr(op, expr, startPos);
    }

    @Override
    public ValueType type() {
        return expr.type();
    }

    @Override
    public PositionRange positionRange() {
        return new PositionRange(startPos, expr.positionRange().end());
    }

    /**
     * 位置字段（startPos）不参与相等性（Q9）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UnaryExpr that)) {
            return false;
        }
        return op == that.op && Objects.equals(expr, that.expr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, expr);
    }
}

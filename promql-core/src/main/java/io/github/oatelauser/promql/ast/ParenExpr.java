package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * 括号表达式：{@code (a + b)}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *ParenExpr}。
 */
public record ParenExpr(
        Expr expr,
        PositionRange posRange) implements Expr {

    public ParenExpr {
        Objects.requireNonNull(expr, "expr 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static ParenExpr of(Expr expr, PositionRange posRange) {
        return new ParenExpr(expr, posRange);
    }

    @Override
    public ValueType type() {
        return expr.type();
    }

    @Override
    public PositionRange positionRange() {
        return posRange;
    }

    /**
     * 位置字段（posRange）不参与相等性（Q9）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParenExpr that)) {
            return false;
        }
        return Objects.equals(expr, that.expr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expr);
    }
}

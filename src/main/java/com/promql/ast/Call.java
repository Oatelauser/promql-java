package com.promql.ast;

import com.promql.functions.Function;
import com.promql.posrange.PositionRange;
import com.promql.value.ValueType;

import java.util.List;
import java.util.Objects;

/**
 * 函数调用：{@code rate(x[5m])}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *Call}。Go 的
 * {@code Args Expressions} 在 Java 里直接用 {@code List<Expr>}
 * （ADR-0003：不移植 Expressions 类型）。
 */
public record Call(
        Function function,
        List<Expr> args,
        PositionRange posRange) implements Expr {

    public Call {
        Objects.requireNonNull(function, "function 不能为空");
        args = List.copyOf(args);
    }

    /**
     * 静态工厂（Q5）。
     */
    public static Call of(Function function, List<Expr> args, PositionRange posRange) {
        return new Call(function, args, posRange);
    }

    @Override
    public ValueType type() {
        return function.returnType();
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
        if (!(o instanceof Call that)) {
            return false;
        }
        return Objects.equals(function, that.function) && Objects.equals(args, that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(function, args);
    }
}

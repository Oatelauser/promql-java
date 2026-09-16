package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * 字符串字面量：{@code "foo"}、{@code 'bar'}、{@code `baz`}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *StringLiteral}。
 * {@code val} 为去引号、完成转义解析后的值。
 */
public record StringLiteral(
        String val,
        PositionRange posRange) implements Expr {

    public StringLiteral {
        Objects.requireNonNull(val, "val 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static StringLiteral of(String val, PositionRange posRange) {
        return new StringLiteral(val, posRange);
    }

    @Override
    public ValueType type() {
        return ValueType.STRING;
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
        if (!(o instanceof StringLiteral that)) {
            return false;
        }
        return Objects.equals(val, that.val);
    }

    @Override
    public int hashCode() {
        return Objects.hash(val);
    }
}

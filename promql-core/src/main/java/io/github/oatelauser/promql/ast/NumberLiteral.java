package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * 数字/时长字面量：{@code 1.5}、{@code 5m}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *NumberLiteral}。与 Go 一致，
 * 值以秒计（{@code 5m} 的 Val == 300）；{@code duration} 标记源文本是否为
 * 时长字面量，打印时据此走时长格式化（Val*1e9 纳秒，见打印器）。
 */
public record NumberLiteral(
        double val,
        /** 源文本是否是时长字面量（如 5m）。 */
        boolean duration,
        PositionRange posRange) implements Expr {

    /**
     * 静态工厂（Q5）。
     */
    public static NumberLiteral of(double val, boolean duration, PositionRange posRange) {
        return new NumberLiteral(val, duration, posRange);
    }

    @Override
    public ValueType type() {
        return ValueType.SCALAR;
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
        if (!(o instanceof NumberLiteral that)) {
            return false;
        }
        return duration == that.duration
                && Double.doubleToLongBits(val) == Double.doubleToLongBits(that.val);
    }

    @Override
    public int hashCode() {
        return Objects.hash(val, duration);
    }
}

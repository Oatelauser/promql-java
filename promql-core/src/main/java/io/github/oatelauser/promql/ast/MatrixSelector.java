package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.Objects;

/**
 * Matrix selector（区间选择器，代码术语沿用 Go 类型名，见 CONTEXT.md）：
 * {@code foo[5m]}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *MatrixSelector}。
 * {@code range} 为纳秒（Q9：long 纳秒 + 自定义格式化器）；
 * {@code rangeExpr} 在区间是时长表达式（如 {@code [2m+3m]}）时非空，
 * 此时 range 为 0。
 */
public record MatrixSelector(
        Expr vectorSelector,
        long range,
        DurationExpr rangeExpr,
        int endPos) implements Expr {

    public MatrixSelector {
        Objects.requireNonNull(vectorSelector, "vectorSelector 不能为空");
    }

    /**
     * 静态工厂（Q5）。
     */
    public static MatrixSelector of(Expr vectorSelector, long range,
            DurationExpr rangeExpr, int endPos) {
        return new MatrixSelector(vectorSelector, range, rangeExpr, endPos);
    }

    @Override
    public ValueType type() {
        return ValueType.MATRIX;
    }

    @Override
    public PositionRange positionRange() {
        return new PositionRange(vectorSelector.positionRange().start(), endPos);
    }

    /**
     * 位置字段（endPos）不参与相等性（Q9）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MatrixSelector that)) {
            return false;
        }
        return range == that.range && Objects.equals(vectorSelector, that.vectorSelector)
                && Objects.equals(rangeExpr, that.rangeExpr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vectorSelector, range, rangeExpr);
    }
}

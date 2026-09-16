package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.List;
import java.util.Objects;

/**
 * 聚合表达式：{@code sum by (a) (expr)}、{@code topk(5, expr)}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *AggregateExpr}。字段：
 * {@code Op/Expr/Param/Grouping/Without/PosRange} 原样保留。
 */
public record AggregateExpr(
        AggregateOp op,
        Expr expr,
        /** 聚合参数（topk 的 5、count_values 的 "le"…）；无参聚合器为 null。 */
        Expr param,
        List<String> grouping,
        boolean without,
        PositionRange posRange) implements Expr {

    public AggregateExpr {
        Objects.requireNonNull(op, "op 不能为空");
        Objects.requireNonNull(expr, "expr 不能为空");
        grouping = List.copyOf(grouping);
    }

    /**
     * 静态工厂（Q5）。
     */
    public static AggregateExpr of(AggregateOp op, Expr expr, Expr param,
            List<String> grouping, boolean without,
            PositionRange posRange) {
        return new AggregateExpr(op, expr, param, grouping, without, posRange);
    }

    @Override
    public ValueType type() {
        return ValueType.VECTOR;
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
        if (!(o instanceof AggregateExpr that)) {
            return false;
        }
        return without == that.without && op == that.op
                && Objects.equals(expr, that.expr) && Objects.equals(param, that.param)
                && Objects.equals(grouping, that.grouping);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, expr, param, grouping, without);
    }
}

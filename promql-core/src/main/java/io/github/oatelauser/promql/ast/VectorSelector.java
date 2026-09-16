package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.labels.LabelMatcher;
import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.value.ValueType;

import java.util.List;
import java.util.Objects;

/**
 * 向量选择器：{@code foo{a="b"} @ 123 offset 5m}。
 *
 * <p>对应 Go {@code parser/ast.go} 的 {@code *VectorSelector}。
 * {@code originalOffset} 为纳秒；{@code timestamp} 为 @ 修饰符的毫秒值
 * （Go *int64 → 可空 Long）；labelMatchers 保持源顺序（Q9），
 * 打印时才排序。
 *
 * <p><b>裁剪字段（ADR-0003，快照核实）</b>：{@code Offset}（仅引擎写入）、
 * {@code Series}/{@code UnexpandedSeriesSet}（存储执行期）、
 * {@code BypassEmptyMatcherCheck}（checkAST 内部状态，info() 参数检查用，
 * 移植版以解析器局部集合实现）、{@code SkipHistogramBuckets}（仅
 * engine.go 写入）。
 */
public record VectorSelector(
        /* 指标名；纯 {…} 选择器为空串。 */
        String name,
        long originalOffset,
        DurationExpr originalOffsetExpr,
        Long timestamp,
        StartOrEnd startOrEnd,
        List<LabelMatcher> labelMatchers,
        boolean anchored,
        boolean smoothed,
        PositionRange posRange) implements Expr {

    public VectorSelector {
        Objects.requireNonNull(name, "name 不能为空（纯 {…} 选择器用空串）");
        Objects.requireNonNull(startOrEnd, "startOrEnd 不能为空（无修饰符时用 NONE）");
        labelMatchers = List.copyOf(labelMatchers);
    }

    /**
     * 静态工厂（Q5）。
     */
    public static VectorSelector of(String name, long originalOffset,
            DurationExpr originalOffsetExpr, Long timestamp,
            StartOrEnd startOrEnd, List<LabelMatcher> labelMatchers,
            boolean anchored, boolean smoothed,
            PositionRange posRange) {
        return new VectorSelector(name, originalOffset, originalOffsetExpr, timestamp,
                startOrEnd, labelMatchers, anchored, smoothed, posRange);
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
        if (!(o instanceof VectorSelector that)) {
            return false;
        }
        return originalOffset == that.originalOffset && anchored == that.anchored
                && smoothed == that.smoothed && startOrEnd == that.startOrEnd
                && Objects.equals(name, that.name)
                && Objects.equals(originalOffsetExpr, that.originalOffsetExpr)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(labelMatchers, that.labelMatchers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, originalOffset, originalOffsetExpr, timestamp,
                startOrEnd, labelMatchers, anchored, smoothed);
    }
}

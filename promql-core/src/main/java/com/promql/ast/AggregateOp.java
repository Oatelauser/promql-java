package com.promql.ast;

/**
 * 聚合运算符，对应 Go {@code parser/lex.go} 的 aggregator 词法单元族
 * （AVG、BOTTOMK、COUNT、…、LIMIT_RATIO）。
 *
 * <p>{@link #symbol()} 为 PromQL 源码写法。
 */
public enum AggregateOp {
    AVG("avg"),
    BOTTOMK("bottomk"),
    COUNT("count"),
    COUNT_VALUES("count_values"),
    GROUP("group"),
    MAX("max"),
    MIN("min"),
    QUANTILE("quantile"),
    STDDEV("stddev"),
    STDVAR("stdvar"),
    SUM("sum"),
    TOPK("topk"),
    LIMITK("limitk"),
    LIMIT_RATIO("limit_ratio");

    private final String symbol;

    AggregateOp(String symbol) {
        this.symbol = symbol;
    }

    /**
     * PromQL 源码写法。
     */
    public String symbol() {
        return symbol;
    }

    /**
     * 是否是带参数的聚合器（topk、bottomk、count_values、quantile、limitk、
     * limit_ratio）。对应 Go {@code ItemType.IsAggregatorWithParam()}。
     */
    public boolean isAggregatorWithParam() {
        return this == TOPK || this == BOTTOMK || this == COUNT_VALUES
                || this == QUANTILE || this == LIMITK || this == LIMIT_RATIO;
    }

    @Override
    public String toString() {
        return symbol;
    }
}

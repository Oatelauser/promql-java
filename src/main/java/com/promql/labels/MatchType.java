package com.promql.labels;

/**
 * 标签匹配器的匹配类型。
 *
 * <p>对应 Go：{@code model/labels.MatchType}（MatchEqual、MatchNotEqual、
 * MatchRegexp、MatchNotRegexp）。{@link #symbol()} 是 PromQL 源码中的运算符。
 */
public enum MatchType {
    /**
     * {@code =}，对应 Go {@code MatchEqual}。
     */
    EQUAL("="),
    /**
     * {@code !=}，对应 Go {@code MatchNotEqual}。
     */
    NOT_EQUAL("!="),
    /**
     * {@code =~}，对应 Go {@code MatchRegexp}。
     */
    REGEXP("=~"),
    /**
     * {@code !~}，对应 Go {@code MatchNotRegexp}。
     */
    NOT_REGEXP("!~");

    private final String symbol;

    MatchType(String symbol) {
        this.symbol = symbol;
    }

    /**
     * PromQL 源码中的运算符写法。
     */
    public String symbol() {
        return symbol;
    }
}

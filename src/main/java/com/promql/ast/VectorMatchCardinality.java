package com.promql.ast;

/**
 * 二元表达式向量匹配的基数，对应 Go
 * {@code parser.VectorMatchCardinality}。
 *
 * <p>{@link #symbol()} 对应 Go 的 String()（用于错误消息）。
 */
public enum VectorMatchCardinality {
    ONE_TO_ONE("one-to-one"),
    MANY_TO_ONE("many-to-one"),
    ONE_TO_MANY("one-to-many"),
    MANY_TO_MANY("many-to-many");

    private final String symbol;

    VectorMatchCardinality(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Go String() 形式（错误消息用）。
     */
    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}

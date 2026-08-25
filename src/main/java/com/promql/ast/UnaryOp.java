package com.promql.ast;

/**
 * 一元运算符（仅正负号），对应 Go 文法 {@code unary_op : ADD | SUB}。
 */
public enum UnaryOp {
    PLUS("+"),
    MINUS("-");

    private final String symbol;

    UnaryOp(String symbol) {
        this.symbol = symbol;
    }

    /**
     * PromQL 源码写法。
     */
    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}

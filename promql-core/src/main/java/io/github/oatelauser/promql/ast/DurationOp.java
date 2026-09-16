package io.github.oatelauser.promql.ast;

/**
 * 时长表达式（DurationExpr）的运算符。
 *
 * <p>Go 里复用词法 ItemType（ADD/SUB/MUL/DIV/MOD/POW 与 STEP/RANGE/
 * MIN_OF/MAX_OF）；Java 用独立枚举（Q13 专用枚举决定）。关键字类的
 * {@link #symbol()} 返回裸词（{@code step}），打印器负责补 {@code ()}。
 */
public enum DurationOp {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    POW("^"),
    /**
     * {@code step()}：当前查询步长（实验）。
     */
    STEP("step"),
    /**
     * {@code range()}：当前查询区间（实验）。
     */
    RANGE("range"),
    /**
     * {@code min_of(l, r)}（实验）。
     */
    MIN_OF("min_of"),
    /**
     * {@code max_of(l, r)}（实验）。
     */
    MAX_OF("max_of");

    private final String symbol;

    DurationOp(String symbol) {
        this.symbol = symbol;
    }

    /**
     * PromQL 源码写法；关键字类返回裸词。
     */
    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}

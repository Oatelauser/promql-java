package io.github.oatelauser.promql.ast;

/**
 * 二元运算符，对应 Go {@code parser/lex.go} 的 operator 词法单元族。
 *
 * <p>注意 trim 运算符的符号：{@code TRIM_UPPER} 为 {@code "</"}，
 * {@code TRIM_LOWER} 为 {@code ">/"}（移植自快照 lex.go 的 ItemTypeStr 表，
 * 勿凭直觉写成 {@code >?}）。
 */
public enum BinaryOp {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    POW("^"),
    ATAN2("atan2"),
    EQLC("=="),
    NEQ("!="),
    GT(">"),
    LT("<"),
    GTE(">="),
    LTE("<="),
    /**
     * {@code </}：截断比较变体（实验语法）。
     */
    TRIM_UPPER("</"),
    /**
     * {@code >/}：截断比较变体（实验语法）。
     */
    TRIM_LOWER(">/"),
    LAND("and"),
    LOR("or"),
    LUNLESS("unless");

    private final String symbol;

    BinaryOp(String symbol) {
        this.symbol = symbol;
    }

    /**
     * PromQL 源码写法。
     */
    public String symbol() {
        return symbol;
    }

    /**
     * 是否为比较运算符（含 trim 变体），对应 Go
     * {@code ItemType.IsComparisonOperator()}。
     */
    public boolean isComparisonOperator() {
        return this == EQLC || this == NEQ || this == GT || this == LT
                || this == GTE || this == LTE || this == TRIM_UPPER || this == TRIM_LOWER;
    }

    /**
     * 是否为集合运算符（and/or/unless），对应 Go {@code ItemType.IsSetOperator()}。
     */
    public boolean isSetOperator() {
        return this == LAND || this == LOR || this == LUNLESS;
    }

    @Override
    public String toString() {
        return symbol;
    }
}

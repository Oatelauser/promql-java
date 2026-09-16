package io.github.oatelauser.promql.value;

/**
 * PromQL 表达式的求值结果类型。
 *
 * <p>对应 Go：{@code promql/parser/value.go} 的 {@code ValueType}。
 *
 * <p>Go 用字符串常量（{@code "none"/"string"/"scalar"/"vector"/"matrix"}），
 * Java 移植为枚举；{@link #symbol()} 返回 Go 的字符串字面量，
 * {@link #documentedType()} 返回面向文档的表述（如 "instant vector"）。
 */
public enum ValueType {
    /**
     * 无值。对应 Go {@code ValueTypeNone}。
     */
    NONE("none"),
    /**
     * 字符串。对应 Go {@code ValueTypeString}。
     */
    STRING("string"),
    /**
     * 标量。对应 Go {@code ValueTypeScalar}。
     */
    SCALAR("scalar"),
    /**
     * 即时向量（instant vector）。对应 Go {@code ValueTypeVector}。
     */
    VECTOR("vector"),
    /**
     * 区间向量（range vector）。对应 Go {@code ValueTypeMatrix}。
     */
    MATRIX("matrix");

    private final String symbol;

    ValueType(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Go 的字符串字面量形式。
     */
    public String symbol() {
        return symbol;
    }

    /**
     * 文档化表述，对应 Go {@code ValueType.DocumentedType()}。
     */
    public String documentedType() {
        return switch (this) {
            case NONE -> "none";
            case STRING -> "string";
            case SCALAR -> "scalar";
            case VECTOR -> "instant vector";
            case MATRIX -> "range vector";
        };
    }

    @Override
    public String toString() {
        return symbol;
    }
}

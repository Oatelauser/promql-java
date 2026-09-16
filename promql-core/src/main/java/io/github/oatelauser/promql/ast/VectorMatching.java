package io.github.oatelauser.promql.ast;

import java.util.List;
import java.util.Objects;

/**
 * 二元表达式的向量匹配描述，对应 Go {@code parser.VectorMatching}。
 *
 * <p>Go 的 {@code FillValues{LHS, RHS *float64}} 在 Java 里展平为两个可空
 * {@link Double}（Go 的 nil ≈ Java 的 null，语义一致：未启用填充）。
 * 不可变值类型（Q5）。
 */
public record VectorMatching(
        VectorMatchCardinality card,
        List<String> matchingLabels,
        boolean on,
        List<String> include,
        Double fillLhs,
        Double fillRhs) {

    public VectorMatching {
        Objects.requireNonNull(card, "card 不能为空");
        matchingLabels = List.copyOf(matchingLabels);
        include = include == null ? List.of() : List.copyOf(include);
    }

    /**
     * 默认 one-to-one、无匹配标签，对应文法 bool_modifier 空产生式。
     */
    public static VectorMatching defaultMatching() {
        return new VectorMatching(VectorMatchCardinality.ONE_TO_ONE, List.of(), false, List.of(), null, null);
    }
}

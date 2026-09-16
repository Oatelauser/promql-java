package io.github.oatelauser.promql.parser;

/**
 * 解析器配置（4 个镜像快照的特性开关），对应 Go {@code parser/parse.go}
 * 的 {@code Options}（源头见 {@code util/features} 注册表）。
 *
 * <p>全部默认关闭；实验语法只有显式开启才被接受，与 Prometheus CLI 的
 * {@code --enable-feature} 行为一致。
 */
public record ParserOptions(
        /** {@code promql-experimental-functions}：实验函数与实验聚合器（limitk/limit_ratio）。 */
        boolean enableExperimentalFunctions,
        /** {@code promql-experimental-duration-expr}：时长算术表达式（{@code [2m+3m]}、offset 表达式）。 */
        boolean experimentalDurationExpr,
        /** {@code promql-experimental-extended-range-selectors}：{@code anchored}/{@code smoothed} 修饰符。 */
        boolean enableExtendedRangeSelectors,
        /** {@code promql-experimental-binop-fill-modifiers}：二元运算 {@code fill}/fill_left/fill_right 修饰符。 */
        boolean enableBinopFillModifiers) {

    /**
     * 全部特性关闭的默认配置。
     */
    public static ParserOptions defaults() {
        return new ParserOptions(false, false, false, false);
    }
}

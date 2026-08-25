package com.promql.labels;

import com.promql.util.GoStrings;

/**
 * 标签匹配器：{@code {job=~"api"}} 中的一个 name/op/value 三元组。
 *
 * <p>对应 Go：{@code model/labels.Matcher}（仅其数据部分）。
 *
 * <p><b>纯数据（Q13 决定）</b>：不移植 {@code Matches()} 求值语义，也不在
 * 本类型上编译正则。解析期对正则语法的校验（对应 Go
 * {@code labels.NewMatcher} 的 {@code regexp.Compile("^(?:"+v+")$")}）由
 * 解析器在构造时用 {@link java.util.regex.Pattern} 完成，校验失败记为
 * 解析错误。正则方言差异（Go RE2 vs Java）见 PORTING.md 的已知分歧清单。
 */
public record LabelMatcher(String name, MatchType type, String value) {

    /**
     * 渲染为 PromQL 片段：{@code name符号"值"}，值经 Go strconv.Quote 风格转义。
     *
     * <p><b>非 legacy 标签名加引号</b>（printer_test.go 黄金用例
     * {@code {"a.b"="c"}} / {@code {"0"="1"}} / {@code {""="0"}} 带引号、
     * {@code {"_0"="1"} → {_0="1"}} 裸写）：与 printer 侧
     * {@link Labels#isValidLegacyLabelName(String)} 同一判定，保证打印产物可回读。
     */
    public String toPromql() {
        String nameStr = Labels.isValidLegacyLabelName(name) ? name : GoStrings.quote(name);
        return nameStr + type.symbol() + GoStrings.quote(value);
    }

    @Override
    public String toString() {
        return toPromql();
    }
}

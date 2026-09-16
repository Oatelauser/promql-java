package io.github.oatelauser.promql.lexer;

import io.github.oatelauser.promql.util.GoStrings;

/**
 * 词法单元，对应 Go {@code parser/lex.go} 的 {@code Item}。
 *
 * <p>{@link #pos()} 是该单元在输入串中的起点。Go 用<strong>字节</strong>
 * 偏移；本库输入域是 Java String，pos 采用 <strong>UTF-16 码元</strong>
 * 偏移——对 ASCII 输入（含全部 conformance 用例）二者一致，非 ASCII
 * 输入下的差异记录在 PORTING.md 已知分歧清单。
 */
public record Item(ItemType typ, int pos, String val) {

    /**
     * 描述性渲染，对应 Go {@code Item.String()}。
     */
    public String string() {
        if (typ == ItemType.EOF) {
            return "EOF";
        }
        if (typ == ItemType.ERROR) {
            return val;
        }
        if (typ == ItemType.IDENTIFIER || typ == ItemType.METRIC_IDENTIFIER) {
            return GoStrings.quote(val);
        }
        if (typ.isKeyword()) {
            return "<" + val + ">";
        }
        if (typ.isOperator()) {
            return "<op:" + val + ">";
        }
        if (typ.isAggregator()) {
            return "<aggr:" + val + ">";
        }
        if (val.length() > 10) {
            // Go fmt 的 %.10q：按 rune 截断到 10 位再引用，随后接字面省略号。
            StringBuilder prefix = new StringBuilder(12);
            val.codePoints().limit(10).forEach(prefix::appendCodePoint);
            return GoStrings.quote(prefix.toString()) + "...";
        }
        return GoStrings.quote(val);
    }

    /**
     * 对应 Go {@code Item.Pretty}（与 {@link #string()} 相同）。
     */
    public String pretty() {
        return string();
    }

    /**
     * 用于错误消息的简短描述，对应 Go {@code Item.desc()}：有符号表的
     * 类型渲染为 {@link #string()}；EOF 之外其余渲染为
     * {@code 类型描述 + 空格 + string()}。
     */
    public String desc() {
        // Go: if _, ok := ItemTypeStr[i.Typ]; ok
        if (hasSymbol()) {
            return string();
        }
        if (typ == ItemType.EOF) {
            return typ.desc();
        }
        return typ.desc() + " " + string();
    }

    private boolean hasSymbol() {
        // 与 ItemType.toString() 的查表逻辑一致：能渲染出符号表值（而非
        // <Item N> 兜底）即视为在 ItemTypeStr 表内。
        String s = typ.toString();
        return !s.startsWith("<Item ");
    }

    @Override
    public String toString() {
        return string();
    }
}

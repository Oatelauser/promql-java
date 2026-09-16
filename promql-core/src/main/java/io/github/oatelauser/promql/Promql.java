package io.github.oatelauser.promql;

import io.github.oatelauser.promql.ast.Expr;
import io.github.oatelauser.promql.labels.Label;
import io.github.oatelauser.promql.labels.LabelMatcher;
import io.github.oatelauser.promql.parser.Parser;
import io.github.oatelauser.promql.parser.ParserOptions;
import io.github.oatelauser.promql.parser.PromqlParseException;
import io.github.oatelauser.promql.printer.Printer;

import java.util.List;

/**
 * PromQL 解析/打印门面（推荐入口），对应 Go 包级函数
 * {@code promql/parser.ParseExpr}、{@code ParseMetricSelector}、{@code ParseMetric}
 * 与 {@code Expr.String()}。
 *
 * <p>典型用法：
 * <pre>{@code
 * Expr ast = Promql.parse("sum(rate(foo[5m])) by (job)");
 * String query = Promql.print(ast);          // "sum by (job) (rate(foo[5m]))"
 * Expr roundTripped = Promql.parse(query);   // 与 ast 语义相等（Q4 保证 P1）
 * }</pre>
 *
 * <p>所有方法无共享可变状态，线程安全。
 */
public final class Promql {

    private Promql() {
    }

    /**
     * 解析 PromQL 表达式（默认关闭全部实验特性）。
     *
     * @param query PromQL 字符串
     * @return 带位置信息与全部语义字段的 AST
     * @throws PromqlParseException 语法或语义错误（携带 {@link io.github.oatelauser.promql.parser.ParseError} 列表，
     *                              首错消息与 Go 实现一致）
     * @throws StackOverflowError   输入嵌套深度超出线程栈容量（Go 靠可增长协程栈
     *                              无此限制；真实查询深度极浅，仅病态输入触及）。
     *                              解法＝加大解析线程栈：{@code -Xss} 或
     *                              {@code new Thread(null, task, name, stackSize)}，
     *                              详见 PORTING.md 已知分歧 4
     */
    public static Expr parse(String query) {
        return Parser.parseExpr(query);
    }

    /**
     * 同 {@link #parse(String)}，可开启实验特性开关。
     */
    public static Expr parse(String query, ParserOptions options) {
        return Parser.parseExpr(query, options);
    }

    /**
     * 不抛异常的解析：失败返回 {@code null}（Q7 的 tryParse 形态）。
     */
    public static Expr tryParse(String query) {
        return tryParse(query, ParserOptions.defaults());
    }

    /**
     * 同 {@link #tryParse(String)}，可开启实验特性开关。
     */
    public static Expr tryParse(String query, ParserOptions options) {
        try {
            return Parser.parseExpr(query, options);
        } catch (PromqlParseException e) {
            return null;
        }
    }

    /**
     * 解析指标选择器（如 {@code foo{a="b"}}，带尾部空白亦可），返回含 {@code __name__} 匹配器的列表。
     */
    public static List<LabelMatcher> parseMetricSelector(String selector) {
        return Parser.parseMetricSelector(selector);
    }

    /**
     * 解析指标描述（如 {@code my_metric{a="b"}} 或 {@code {a="b"}}），返回按名排序的标签列表。
     */
    public static List<Label> parseMetric(String metric) {
        return Parser.parseMetric(metric);
    }

    /**
     * 由 AST 还原 PromQL 字符串（对应 Go {@code Expr.String()}）。
     */
    public static String print(Expr ast) {
        return Printer.toPromql(ast);
    }
}

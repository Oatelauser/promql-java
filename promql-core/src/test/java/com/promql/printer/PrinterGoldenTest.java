package com.promql.printer;

import com.promql.ast.Expr;
import com.promql.parser.Parser;
import com.promql.parser.ParserOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 打印黄金输出测试：{@code printer_test.go} 的
 * {@code TestExprString}（86 条）与 {@code TestBinaryExprUTF8Labels}
 * （6 条）<b>逐字移植</b>。
 *
 * <p>与既有测试的分工：{@code ParserConformanceTest} 只断言打印<b>幂等</b>
 * （print∘parse∘print == print），不断言打印<b>内容</b>；本类把 Go 期望的
 * 规范化输出逐条钉死——聚合/匹配子句的空格、{@code @ %.3f}、时长表达式
 * 的括号丢弃规则、fill 收敛、非 legacy 标签加引号等规范化行为一目了然。
 *
 * <p>选项与 Go 对齐：{@code TestExprString} 的 optsParser 开启
 * durationExpr/extendedRange/binopFill（不开 experimentalFunctions）；
 * {@code TestBinaryExprUTF8Labels} 用 testParser（全开）。
 */
@DisplayName("打印黄金输出：printer_test.go 逐字移植")
class PrinterGoldenTest {

    /** Go TestExprString 的 optsParser：三开一关。 */
    private static final ParserOptions OPTS = new ParserOptions(false, true, true, true);
    /** Go printer_test.go 的 testParser：全部实验开关打开。 */
    private static final ParserOptions FULL = new ParserOptions(true, true, true, true);

    private record Case(String in, String out) {
    }

    @TestFactory
    @DisplayName("TestExprString 86 条：打印内容与 Go 黄金值逐字一致")
    List<DynamicTest> exprString() {
        List<Case> cases = List.of(
                // ---- 聚合子句 ----
                new Case("sum by() (task:errors:rate10s{job=\"s\"})",
                        "sum(task:errors:rate10s{job=\"s\"})"),
                new Case("sum by(code) (task:errors:rate10s{job=\"s\"})",
                        "sum by (code) (task:errors:rate10s{job=\"s\"})"),
                new Case("sum without() (task:errors:rate10s{job=\"s\"})",
                        "sum without () (task:errors:rate10s{job=\"s\"})"),
                new Case("sum without(instance) (task:errors:rate10s{job=\"s\"})",
                        "sum without (instance) (task:errors:rate10s{job=\"s\"})"),
                new Case("sum by(\"foo.bar\") (task:errors:rate10s{job=\"s\"})",
                        "sum by (\"foo.bar\") (task:errors:rate10s{job=\"s\"})"),
                new Case("sum without(\"foo.bar\") (task:errors:rate10s{job=\"s\"})",
                        "sum without (\"foo.bar\") (task:errors:rate10s{job=\"s\"})"),
                new Case("topk(5, task:errors:rate10s{job=\"s\"})", ""),
                new Case("count_values(\"value\", task:errors:rate10s{job=\"s\"})", ""),

                // ---- 二元匹配子句 ----
                new Case("a - on() c", "a - on () c"),
                new Case("a - on(b) c", "a - on (b) c"),
                new Case("a - on(b) group_left(x) c", "a - on (b) group_left (x) c"),
                new Case("a - on(b) group_left(x, y) c", "a - on (b) group_left (x, y) c"),
                new Case("a - on(b) group_left c", "a - on (b) group_left () c"),
                new Case("a - on(b) group_left() (c)", "a - on (b) group_left () (c)"),
                new Case("a - ignoring(b) c", "a - ignoring (b) c"),
                new Case("a - ignoring() c", "a - c"),
                // 空 ignoring() + group_left：语法上必须显式保留 ignoring() 才能用
                // group_x(__name__)，因此打印不丢（Go 注释原意）。
                new Case("a - ignoring() group_left(__metric__) c",
                        "a - ignoring () group_left (__metric__) c"),
                new Case("a - ignoring() group_left c", "a - ignoring () group_left () c"),

                // ---- fill 修饰符 ----
                new Case("a + fill(-23) b", "a + fill (-23) b"),
                new Case("a + fill_left(-23) b", "a + fill_left (-23) b"),
                new Case("a + fill_right(42) b", "a + fill_right (42) b"),
                new Case("a + fill_left(-23) fill_right(42) b",
                        "a + fill_left (-23) fill_right (42) b"),
                new Case("a + fill_left(5) fill_right(5) b", "a + fill (5) b"),
                new Case("a + on(b) group_left fill(-23) c",
                        "a + on (b) group_left () fill (-23) c"),

                // ---- offset / @ / anchored / smoothed ----
                new Case("up > bool 0", ""),
                new Case("a offset 1m", ""),
                new Case("a offset -7m", ""),
                new Case("a{c=\"d\"}[5m] offset 1m", ""),
                new Case("a[5m] offset 1m", ""),
                new Case("a[12m] offset -3m", ""),
                new Case("a[1h:5m] offset 1m", ""),
                new Case("a anchored", ""),
                new Case("a[5m] anchored", ""),
                new Case("a{b=\"c\"}[5m] anchored", ""),
                new Case("a{b=\"c\"}[5m] anchored offset 1m", ""),
                new Case("a{b=\"c\"}[5m] anchored @ start() offset 1m", ""),
                new Case("a smoothed", ""),
                new Case("a[5m] smoothed", ""),
                new Case("a{b=\"c\"}[5m] smoothed", ""),
                new Case("a{b=\"c\"}[5m] smoothed offset 1m", ""),
                new Case("a{b=\"c\"}[5m] smoothed @ start() offset 1m", ""),

                // ---- 匹配器与标签名 ----
                new Case("{__name__=\"a\"}", ""),
                new Case("a{b!=\"c\"}[1m]", ""),
                new Case("a{b=~\"c\"}[1m]", ""),
                new Case("a{b!~\"c\"}[1m]", ""),
                new Case("a @ 10", "a @ 10.000"),
                new Case("a[1m] @ 10", "a[1m] @ 10.000"),
                new Case("a @ start()", ""),
                new Case("a @ end()", ""),
                new Case("a[1m] @ start()", ""),
                new Case("a[1m] @ end()", ""),
                new Case("{__name__=\"\",a=\"x\"}", ""),
                new Case("{\"a.b\"=\"c\"}", ""),
                new Case("{\"0\"=\"1\"}", ""),
                new Case("{\"_0\"=\"1\"}", "{_0=\"1\"}"),
                new Case("{\"\"=\"0\"}", ""),
                // 空反引号串作标签名 → 打印成 "" 引号形式
                new Case("{``=\"0\"}", "{\"\"=\"0\"}"),
                new Case("1048576", ""),

                // ---- 时长表达式（ExperimentalDurationExpr）----
                new Case("foo[step()]", ""),
                new Case("foo[-step()]", ""),
                new Case("foo[(step())]", ""),
                new Case("foo[-(step())]", ""),
                new Case("foo offset step()", ""),
                new Case("foo offset -step()", ""),
                new Case("foo offset (step())", ""),
                new Case("foo offset -(step())", ""),
                new Case("foo offset +(5)", "foo offset (5)"),
                new Case("foo offset -(5)", ""),
                new Case("foo offset (5)", ""),
                new Case("foo offset (5m)", ""),
                new Case("foo[(5s)]", ""),
                new Case("foo[(5m):(1m)]", ""),
                new Case("foo offset +min_of(10s, 20s)", "foo offset min_of(10s, 20s)"),
                new Case("foo offset -min_of(10s, 20s)", ""),
                new Case("foo offset -min_of(10s, +max_of(step() ^ 2, 2))",
                        "foo offset -min_of(10s, max_of(step() ^ 2, 2))"),
                new Case("foo[200-min_of(-step()^+step(),1)]",
                        "foo[200 - min_of(-step() ^ step(), 1)]"),
                new Case("foo[200 - min_of(step() + 10s, -max_of(step() ^ 2, 3))]", ""),
                new Case("foo[range()]", ""),
                new Case("foo[-range()]", ""),
                new Case("foo offset range()", ""),
                new Case("foo offset -range()", ""),
                new Case("foo[max_of(range(), 5s)]", ""),
                new Case("predict_linear(foo[1h], 3000)", ""),

                // ---- UTF-8 分组标签（打印侧按 legacy 规则加引号）----
                new Case("sum by(\"üüü\") (foo)", "sum by (\"üüü\") (foo)"),
                new Case("sum without(\"äää\") (foo)", "sum without (\"äää\") (foo)"),
                new Case("count by(\"ööö\", job) (foo)", "count by (\"ööö\", job) (foo)"));

        List<DynamicTest> tests = new ArrayList<>(cases.size());
        for (Case c : cases) {
            String expected = c.out().isEmpty() ? c.in() : c.out();
            tests.add(DynamicTest.dynamicTest(c.in(), () -> {
                Expr expr = Parser.parseExpr(c.in(), OPTS);
                assertEquals(expected, Printer.toPromql(expr),
                        "打印输出与 Go printer_test.go 黄金值不符: " + c.in());
            }));
        }
        return tests;
    }

    /**
     * {@code TestBinaryExprUTF8Labels}：on/ignoring/group_x 子句里混合
     * legacy 与 UTF-8 标签——legacy 裸写、非 legacy 加引号。
     */
    @TestFactory
    @DisplayName("TestBinaryExprUTF8Labels 6 条：on/ignoring/group_x 混合 UTF-8 标签")
    List<DynamicTest> binaryExprUtf8Labels() {
        List<Case> cases = List.of(
                new Case("foo / on(\"äää\") bar", "foo / on (\"äää\") bar"),
                new Case("foo / ignoring(\"üüü\") bar", "foo / ignoring (\"üüü\") bar"),
                new Case("foo / on(\"äää\") group_left(\"ööö\") bar",
                        "foo / on (\"äää\") group_left (\"ööö\") bar"),
                new Case("foo / on(\"äää\") group_right(\"ööö\") bar",
                        "foo / on (\"äää\") group_right (\"ööö\") bar"),
                new Case("foo / on(legacy, \"üüü\") bar", "foo / on (legacy, \"üüü\") bar"),
                new Case("foo / on(job, instance) bar", "foo / on (job, instance) bar"));

        return cases.stream()
                .map(c -> DynamicTest.dynamicTest(c.in(), () -> {
                    Expr expr = Parser.parseExpr(c.in(), FULL);
                    assertEquals(c.out(), Printer.toPromql(expr));
                }))
                .toList();
    }
}

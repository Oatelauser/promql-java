package io.github.oatelauser.promql.parser;

import io.github.oatelauser.promql.Promql;
import io.github.oatelauser.promql.ast.AggregateExpr;
import io.github.oatelauser.promql.ast.AggregateOp;
import io.github.oatelauser.promql.ast.BinaryExpr;
import io.github.oatelauser.promql.ast.BinaryOp;
import io.github.oatelauser.promql.ast.Call;
import io.github.oatelauser.promql.ast.DurationExpr;
import io.github.oatelauser.promql.ast.DurationOp;
import io.github.oatelauser.promql.ast.Expr;
import io.github.oatelauser.promql.ast.MatrixSelector;
import io.github.oatelauser.promql.ast.NumberLiteral;
import io.github.oatelauser.promql.ast.ParenExpr;
import io.github.oatelauser.promql.ast.StartOrEnd;
import io.github.oatelauser.promql.ast.StringLiteral;
import io.github.oatelauser.promql.ast.SubqueryExpr;
import io.github.oatelauser.promql.ast.UnaryExpr;
import io.github.oatelauser.promql.ast.UnaryOp;
import io.github.oatelauser.promql.ast.VectorMatchCardinality;
import io.github.oatelauser.promql.ast.VectorMatching;
import io.github.oatelauser.promql.ast.VectorSelector;
import io.github.oatelauser.promql.functions.Functions;
import io.github.oatelauser.promql.labels.LabelMatcher;
import io.github.oatelauser.promql.labels.MatchType;
import io.github.oatelauser.promql.posrange.PositionRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>字符串 → AST</b> 方向测试：PromQL 字符串解析为逐字段断言的 AST。
 *
 * <p>表驱动（{@code input → 期望 AST}），期望 AST 完全用静态工厂手工构造，
 * 与解析产物用 record equals 比较（Q9：equals 忽略位置字段——位置口径由
 * {@link ParserAstConformanceTest} 单独抽样断言）。
 *
 * <p>与既有测试的分工：
 * <ul>
 *   <li>{@link ParserConformanceTest}：官方 352 条全量，断言"解析成败 + 错误消息"；</li>
 *   <li>{@link ParserAstConformanceTest}：官方表 22 例逐字段抽样；</li>
 *   <li>本类：<b>正向构造</b> 37 条——每类节点至少一条，含负 offset、
 *       十六进制数字、全部四种匹配器、布尔修饰符、fill、时长表达式等
 *       官方抽样未单独展开的形态；另有 8 组实战查询双向往返用例。</li>
 * </ul>
 */
@DisplayName("字符串 → AST：表驱动逐字段断言 + 实战双向往返")
class StringToAstTest {

    private static final PositionRange P = new PositionRange(0, 0);
    private static final ParserOptions DEF = ParserOptions.defaults();
    /** 实验开关全开（时长表达式 / 扩展区间 / fill）。 */
    private static final ParserOptions EXP = new ParserOptions(false, true, true, true);

    private record Case(String in, ParserOptions opts, Expr expected) {
    }

    private static VectorSelector vs(String name, LabelMatcher... ms) {
        return VectorSelector.of(name, 0L, null, null, StartOrEnd.NONE, List.of(ms), false, false, P);
    }

    private static LabelMatcher eq(String n, String v) {
        return new LabelMatcher(n, MatchType.EQUAL, v);
    }

    private static NumberLiteral num(double v) {
        return NumberLiteral.of(v, false, P);
    }

    private static NumberLiteral dur(double seconds) {
        return NumberLiteral.of(seconds, true, P);
    }

    @TestFactory
    @DisplayName("字符串 → AST：表驱动逐字段断言")
    List<DynamicTest> stringToAst() {
        List<Case> cases = List.of(
                // ---- 字面量 ----
                new Case("1", DEF, num(1)),
                new Case("1.5e3", DEF, num(1500)),
                new Case("-3", DEF, num(-3)),
                new Case("0x10", DEF, num(16)),
                new Case("5m", DEF, dur(300)),
                new Case("\"a\\\"b\"", DEF, StringLiteral.of("a\"b", P)),

                // ---- 向量选择器 ----
                new Case("foo", DEF, vs("foo", eq("__name__", "foo"))),
                new Case("{__name__=\"foo\"}", DEF, vs("", eq("__name__", "foo"))),
                new Case("foo{a=\"1\",b!=\"2\",c=~\"3\",d!~\"4\"}", DEF,
                        vs("foo",
                                eq("a", "1"),
                                new LabelMatcher("b", MatchType.NOT_EQUAL, "2"),
                                new LabelMatcher("c", MatchType.REGEXP, "3"),
                                new LabelMatcher("d", MatchType.NOT_REGEXP, "4"),
                                eq("__name__", "foo"))),

                // ---- offset / @ / anchored ----
                new Case("foo offset 5m", DEF, VectorSelector.of("foo", 300_000_000_000L,
                        null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P)),
                new Case("foo offset -7m", DEF, VectorSelector.of("foo", -420_000_000_000L,
                        null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P)),
                new Case("foo @ 123.5", DEF, VectorSelector.of("foo", 0L,
                        null, 123_500L, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P)),
                new Case("foo @ start()", DEF, VectorSelector.of("foo", 0L,
                        null, null, StartOrEnd.START,
                        List.of(eq("__name__", "foo")), false, false, P)),
                // anchored 属实验语法，需 extendedRangeSelectors（其余 offset/@ 用 DEF）
                new Case("foo anchored offset 1m", EXP, VectorSelector.of("foo", 60_000_000_000L,
                        null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), true, false, P)),

                // ---- 矩阵 / 子查询 ----
                new Case("foo[5m]", DEF, MatrixSelector.of(
                        vs("foo", eq("__name__", "foo")), 300_000_000_000L, null, 0)),
                new Case("foo[5m:1m]", DEF, SubqueryExpr.of(
                        vs("foo", eq("__name__", "foo")),
                        300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        60_000_000_000L, null, 0)),
                new Case("foo[5m:]", DEF, SubqueryExpr.of(
                        vs("foo", eq("__name__", "foo")),
                        300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        0L, null, 0)),
                new Case("rate(foo[5m])[30m:1m]", DEF, SubqueryExpr.of(
                        Call.of(Functions.getFunction("rate"), List.of(MatrixSelector.of(
                                vs("foo", eq("__name__", "foo")), 300_000_000_000L, null, 0)), P),
                        1_800_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        60_000_000_000L, null, 0)),

                // ---- 一元 / 括号 / 二元 ----
                new Case("(1 + 2) * 3", DEF, BinaryExpr.of(BinaryOp.MUL,
                        ParenExpr.of(BinaryExpr.of(BinaryOp.ADD, num(1), num(2), null, false), P),
                        num(3), null, false)),
                new Case("2 ^ 3 ^ 2", DEF, BinaryExpr.of(BinaryOp.POW, num(2),
                        BinaryExpr.of(BinaryOp.POW, num(3), num(2), null, false), null, false)),
                new Case("-foo", DEF, UnaryExpr.of(UnaryOp.MINUS,
                        vs("foo", eq("__name__", "foo")), 0)),
                new Case("a + b", DEF, BinaryExpr.of(BinaryOp.ADD,
                        vs("a", eq("__name__", "a")), vs("b", eq("__name__", "b")),
                        new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                                List.of(), false, List.of(), null, null), false)),
                new Case("a + on(x, y) group_left(z) b", DEF, BinaryExpr.of(BinaryOp.ADD,
                        vs("a", eq("__name__", "a")), vs("b", eq("__name__", "b")),
                        new VectorMatching(VectorMatchCardinality.MANY_TO_ONE,
                                List.of("x", "y"), true, List.of("z"), null, null), false)),
                new Case("a and b", DEF, BinaryExpr.of(BinaryOp.LAND,
                        vs("a", eq("__name__", "a")), vs("b", eq("__name__", "b")),
                        new VectorMatching(VectorMatchCardinality.MANY_TO_MANY,
                                List.of(), false, List.of(), null, null), false)),
                new Case("a unless on(x) b", DEF, BinaryExpr.of(BinaryOp.LUNLESS,
                        vs("a", eq("__name__", "a")), vs("b", eq("__name__", "b")),
                        new VectorMatching(VectorMatchCardinality.MANY_TO_MANY,
                                List.of("x"), true, List.of(), null, null), false)),
                new Case("1 == bool 2", DEF, BinaryExpr.of(BinaryOp.EQLC,
                        num(1), num(2), null, true)),

                // ---- 聚合 ----
                new Case("sum(x)", DEF, AggregateExpr.of(AggregateOp.SUM,
                        vs("x", eq("__name__", "x")), null, List.of(), false, P)),
                new Case("sum by (a, b) (x)", DEF, AggregateExpr.of(AggregateOp.SUM,
                        vs("x", eq("__name__", "x")), null, List.of("a", "b"), false, P)),
                new Case("sum without (a) (x)", DEF, AggregateExpr.of(AggregateOp.SUM,
                        vs("x", eq("__name__", "x")), null, List.of("a"), true, P)),
                new Case("topk(3, x)", DEF, AggregateExpr.of(AggregateOp.TOPK,
                        vs("x", eq("__name__", "x")), num(3), List.of(), false, P)),
                new Case("count_values(\"v\", x)", DEF, AggregateExpr.of(AggregateOp.COUNT_VALUES,
                        vs("x", eq("__name__", "x")), StringLiteral.of("v", P), List.of(), false, P)),

                // ---- 函数 ----
                new Case("clamp(v, 1, 2)", DEF, Call.of(Functions.getFunction("clamp"), List.of(
                        vs("v", eq("__name__", "v")), num(1), num(2)), P)),
                new Case("histogram_count(foo)", DEF, Call.of(
                        Functions.getFunction("histogram_count"), List.of(
                                vs("foo", eq("__name__", "foo"))), P)),

                // ---- 实验语法 ----
                new Case("foo[2*3m]", EXP, MatrixSelector.of(
                        vs("foo", eq("__name__", "foo")), 0L,
                        DurationExpr.of(DurationOp.MUL, num(2), dur(180), false, 0, 0), 0)),
                new Case("foo[5m:step()]", EXP, SubqueryExpr.of(
                        vs("foo", eq("__name__", "foo")),
                        300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        0L, DurationExpr.of(DurationOp.STEP, null, null, false, 0, 0), 0)),
                new Case("foo offset -(10s-5s)", EXP, VectorSelector.of("foo", 0L,
                        DurationExpr.of(DurationOp.SUB, null,
                                DurationExpr.of(DurationOp.SUB, dur(10), dur(5), true, 0, 0),
                                false, 0, 0),
                        null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P)),
                new Case("a + fill(1) b", EXP, BinaryExpr.of(BinaryOp.ADD,
                        vs("a", eq("__name__", "a")), vs("b", eq("__name__", "b")),
                        new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                                List.of(), false, List.of(), 1.0, 1.0), false)));

        return cases.stream()
                .map(c -> DynamicTest.dynamicTest("字符串→AST: " + c.in(), () ->
                        assertEquals(c.expected(), Parser.parseExpr(c.in(), c.opts()),
                                "字符串→AST 不符: " + c.in())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 实战级完整查询：字符串 ⇄ AST 双向转换（main 演示 + realWorldRoundTrip 断言）
    // ------------------------------------------------------------------

    private record RealQuery(String name, String promql, ParserOptions opts) {
    }

    /**
     * 实战规模的 PromQL：多行、注释、嵌套聚合/子查询/匹配子句。
     * 第一条是本类 main 方法的原始示例；其余为同量级的补充。
     */
    private static final List<RealQuery> REAL_WORLD = List.of(
            new RealQuery("服务 5xx 错误率 + 环比上周翻倍", """
                    (
                      # 1. 计算当前的 5xx 错误率
                      sum by (service) (rate(http_requests_total{env="prod", status=~"5..", method!="GET"}[5m]))
                      /
                      sum by (service) (rate(http_requests_total{env="prod", method!="GET"}[5m]))
                      > 0.05
                    )
                    and
                    (
                      # 2. 比较当前错误率是否是上周同一时间的 2 倍以上
                      sum by (service) (rate(http_requests_total{env="prod", status=~"5..", method!="GET"}[5m]))
                      /
                      sum by (service) (rate(http_requests_total{env="prod", status=~"5..", method!="GET"}[5m] offset 1w))
                      > 2
                    )""", DEF),

            new RealQuery("主机 CPU 非空闲占比", """
                    100 - (
                      # 每个 instance 的空闲+iowait 占比，再按主机聚合
                      avg by (instance) (
                        irate(node_cpu_seconds_total{mode=~"idle|iowait"}[5m])
                      ) * 100
                    )""", DEF),

            new RealQuery("接口延迟 P99（直方图分位数）", """
                    histogram_quantile(
                      0.99,
                      sum by (le, job, instance) (
                        rate(http_request_duration_seconds_bucket{env=~"prod|staging"}[10m])
                      )
                    )""", DEF),

            new RealQuery("内存使用率（子查询窗口平均 + clamp_min）", """
                    clamp_min(
                      100 * avg_over_time(
                        (
                          1 - node_memory_MemAvailable_bytes{job="node"}
                              / node_memory_MemTotal_bytes{job="node"}
                        )[1h:5m]
                      ),
                      0
                    )""", DEF),

            new RealQuery("K8s 重启增量 与 OOM 原因联查", """
                    (
                      kube_pod_container_status_restarts_total{namespace="prod"}
                      - kube_pod_container_status_restarts_total{namespace="prod"} offset 10m
                    ) > 0
                    and on (namespace, pod)
                    min_over_time(
                      kube_pod_container_status_last_reason_reason{namespace="prod", reason="OOMKilled"}[10m]
                    ) > 0""", DEF),

            new RealQuery("topk + label_replace 拼接主机名", """
                    label_replace(
                      topk(
                        5,
                        sum by (job, instance) (rate(node_load1[5m]))
                      ),
                      "host",
                      "$1",
                      "instance",
                      "(.*):.*"
                    )""", DEF),

            new RealQuery("告警抑制：当下故障 但 上周同刻正常", """
                    (
                      up{job="api"} == 0
                      or
                      absent(up{job="api"}) == bool 1
                    )
                    unless on (job, instance)
                      up{job="api"} offset 1w == 1""", DEF),

            new RealQuery("实验语法全家桶（时长表达式/anchored/fill/@ end）", """
                    sum by (job) (
                      rate(foo{env="prod"}[2*3m] anchored @ end() offset -(1h - 30m))
                    )
                    + on (job) group_left fill(1)
                      sum by (job) (rate(bar{env="prod"}[1h:step()]))""", EXP));

    /**
     * 实战查询的双向转换断言：
     * <ol>
     *   <li>字符串 → AST（解析成功且 AST 含完整数据）；</li>
     *   <li>AST → 字符串（打印为规范化 PromQL）；</li>
     *   <li>规范化输出再走一遍 字符串 → AST（解析成功）；</li>
     *   <li>再走一遍 AST → 字符串：与第 2 步输出逐字相同——<b>语义等价按
     *       P1 契约以打印输出比对</b>（打印会把匹配器按字母序规范化，
     *       Go {@code sort.Strings} 同源，reparse 的 AST 与源 AST 在匹配器
     *       顺序上可能不同，语义无损）。</li>
     * </ol>
     */
    private static void assertRoundTrip(RealQuery q) {
        Expr ast1 = Parser.parseExpr(q.promql(), q.opts());
        String printed1 = Promql.print(ast1);
        Expr ast2 = Parser.parseExpr(printed1, q.opts());
        assertEquals(printed1, Promql.print(ast2), "往返打印不稳定: " + q.name());
    }

    @Test
    @DisplayName("实战往返①：服务 5xx 错误率 + 环比上周翻倍")
    void roundTrip5xxErrorRate() {
        assertRoundTrip(REAL_WORLD.get(0));
    }

    @Test
    @DisplayName("实战往返②：主机 CPU 非空闲占比")
    void roundTripHostCpuNonIdle() {
        assertRoundTrip(REAL_WORLD.get(1));
    }

    @Test
    @DisplayName("实战往返③：接口延迟 P99（直方图分位数）")
    void roundTripHistogramP99() {
        assertRoundTrip(REAL_WORLD.get(2));
    }

    @Test
    @DisplayName("实战往返④：内存使用率（子查询窗口平均 + clamp_min）")
    void roundTripMemoryUsage() {
        assertRoundTrip(REAL_WORLD.get(3));
    }

    @Test
    @DisplayName("实战往返⑤：K8s 重启增量与 OOM 原因联查")
    void roundTripK8sRestartOomJoin() {
        assertRoundTrip(REAL_WORLD.get(4));
    }

    @Test
    @DisplayName("实战往返⑥：topk + label_replace 拼接主机名")
    void roundTripTopkLabelReplace() {
        assertRoundTrip(REAL_WORLD.get(5));
    }

    @Test
    @DisplayName("实战往返⑦：告警抑制：当下故障但上周同刻正常")
    void roundTripAlertSuppression() {
        assertRoundTrip(REAL_WORLD.get(6));
    }

    @Test
    @DisplayName("实战往返⑧：实验语法全家桶（时长表达式/anchored/fill/@ end）")
    void roundTripExperimentalSyntax() {
        assertRoundTrip(REAL_WORLD.get(7));
    }

}

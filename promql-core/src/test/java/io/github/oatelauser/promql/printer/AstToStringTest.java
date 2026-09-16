package io.github.oatelauser.promql.printer;

import io.github.oatelauser.promql.ast.AggregateExpr;
import io.github.oatelauser.promql.ast.AggregateOp;
import io.github.oatelauser.promql.ast.BinaryExpr;
import io.github.oatelauser.promql.ast.BinaryOp;
import io.github.oatelauser.promql.ast.Call;
import io.github.oatelauser.promql.ast.DurationExpr;
import io.github.oatelauser.promql.ast.DurationOp;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>AST → 字符串</b> 方向测试：<b>手工构造</b>的 AST 直接喂给
 * {@link Printer#toPromql}，断言精确输出——全程不经过解析器。
 *
 * <p>这是打印器此前唯一零覆盖的测试形态（既有 352 条一致性用例的打印断言
 * 都以 parse 为起点）。期望值来源：
 * <ul>
 *   <li>{@code printer_test.go} 的 {@code TestVectorSelector_String}（8 例）
 *       逐字移植（见 {@link #vectorSelectorGoCases}）；</li>
 *   <li>其余节点类型按 Go 各 {@code String()} 方法推导，并用
 *       {@code TestExprString} 的黄金用例交叉验证
 *       （见 {@code PrinterGoldenTest}）。</li>
 * </ul>
 */
@DisplayName("AST → 字符串：手工构造 AST 直接打印（不经解析器）")
class AstToStringTest {

    private static final PositionRange P = new PositionRange(0, 0);

    private static void assertPrints(String expected, Object node) {
        assertEquals(expected, Printer.toPromql((io.github.oatelauser.promql.ast.Node) node));
    }

    private static NumberLiteral num(double v) {
        return NumberLiteral.of(v, false, P);
    }

    private static NumberLiteral dur(double seconds) {
        return NumberLiteral.of(seconds, true, P);
    }

    private static DurationExpr step() {
        return DurationExpr.of(DurationOp.STEP, null, null, false, 0, 0);
    }

    private static VectorSelector vs(String name, LabelMatcher... ms) {
        return VectorSelector.of(name, 0L, null, null, StartOrEnd.NONE, List.of(ms), false, false, P);
    }

    private static LabelMatcher eq(String n, String v) {
        return new LabelMatcher(n, MatchType.EQUAL, v);
    }

    // ------------------------------------------------------------------
    // 字面量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数字字面量打印：整数/小数/负数")
    void numberLiterals() {
        assertPrints("1", num(1));
        assertPrints("1.5", num(1.5));
        assertPrints("-1.5", num(-1.5));
        assertPrints("0.1", num(0.1));
        assertPrints("1048576", num(1048576));
    }

    @Test
    @DisplayName("时长字面量打印：单位分解（1m30s、1d、负时长）")
    void durationLiterals() {
        assertPrints("1s", dur(1));
        assertPrints("500ms", dur(0.5));
        assertPrints("1m", dur(60));
        assertPrints("1m30s", dur(90));
        assertPrints("5m", dur(300));
        assertPrints("1h", dur(3600));
        assertPrints("1d", dur(86400));
        // 负时长字面量（解析器把一元负号折叠进字面量后打印会回到这里）
        assertPrints("-5m", dur(-300));
    }

    @Test
    @DisplayName("字符串字面量打印：转义与非 ASCII 字符")
    void stringLiterals() {
        assertPrints("\"foo\"", StringLiteral.of("foo", P));
        assertPrints("\"a\\\"b\"", StringLiteral.of("a\"b", P));
        assertPrints("\"tab\\there\"", StringLiteral.of("tab\there", P));
        assertPrints("\"back\\\\slash\"", StringLiteral.of("back\\slash", P));
        // Go strconv.Quote 保留可打印的非 ASCII 字符
        assertPrints("\"ünïcode\"", StringLiteral.of("ünïcode", P));
    }

    // ------------------------------------------------------------------
    // 向量选择器：printer_test.go TestVectorSelector_String 8 例逐字移植
    // ------------------------------------------------------------------

    @Test
    @DisplayName("向量选择器：Go TestVectorSelector_String 8 例逐字移植")
    void vectorSelectorGoCases() {
        // 1. empty value
        assertPrints("", vs(""));
        // 2. no matchers with name
        assertPrints("foobar", vs("foobar"));
        // 3. one matcher with name
        assertPrints("foobar{a=\"x\"}", vs("foobar", eq("a", "x")));
        // 4. two matchers with name
        assertPrints("foobar{a=\"x\",b=\"y\"}", vs("foobar", eq("a", "x"), eq("b", "y")));
        // 5. two matchers without name
        assertPrints("{a=\"x\",b=\"y\"}", vs("", eq("a", "x"), eq("b", "y")));
        // 6. name matcher and name：__name__ 等于指标名 → 跳过
        assertPrints("foobar", vs("foobar", eq("__name__", "foobar")));
        // 7. name matcher only：无指标名，__name__ 匹配器保留
        assertPrints("{__name__=\"foobar\"}", vs("", eq("__name__", "foobar")));
        // 8. empty name matcher：__name__="" 与空名不算"等于指标名" → 保留
        assertPrints("{__name__=\"\",a=\"x\"}", vs("", eq("__name__", ""), eq("a", "x")));
    }

    @Test
    @DisplayName("向量选择器：匹配器排序、四种匹配类型、非 legacy 标签名加引号")
    void vectorSelectorSortingAndMatchTypes() {
        // 匹配器按打印串排序（源顺序 b,a → a,b）
        assertPrints("{a=\"x\",b=\"y\"}", vs("", eq("b", "y"), eq("a", "x")));
        assertPrints("foo{a!=\"x\"}", vs("foo",
                new LabelMatcher("a", MatchType.NOT_EQUAL, "x")));
        assertPrints("foo{a=~\"x\"}", vs("foo",
                new LabelMatcher("a", MatchType.REGEXP, "x")));
        assertPrints("foo{a!~\"x\"}", vs("foo",
                new LabelMatcher("a", MatchType.NOT_REGEXP, "x")));
        // 非 legacy 标签名加引号（printer_test.go：{"a.b"="c"}、{"0"="1"}、{""="0"}）
        assertPrints("{\"a.b\"=\"c\"}", vs("", eq("a.b", "c")));
        assertPrints("{\"0\"=\"1\"}", vs("", eq("0", "1")));
        assertPrints("{\"\"=\"0\"}", vs("", eq("", "0")));
        // legacy 名（_0 以下划线开头）裸写
        assertPrints("{_0=\"1\"}", vs("", eq("_0", "1")));
    }

    @Test
    @DisplayName("向量选择器修饰符：@ → anchored/smoothed → offset 顺序")
    void vectorSelectorModifiers() {
        // 顺序：@ → anchored/smoothed → offset
        assertPrints("foo offset 5m", VectorSelector.of("foo", 300_000_000_000L,
                null, null, StartOrEnd.NONE, List.of(eq("__name__", "foo")), false, false, P));
        assertPrints("foo offset -7m", VectorSelector.of("foo", -420_000_000_000L,
                null, null, StartOrEnd.NONE, List.of(eq("__name__", "foo")), false, false, P));
        assertPrints("foo @ 100.000", VectorSelector.of("foo", 0L,
                null, 100_000L, StartOrEnd.NONE, List.of(eq("__name__", "foo")), false, false, P));
        assertPrints("foo @ end()", VectorSelector.of("foo", 0L,
                null, null, StartOrEnd.END, List.of(eq("__name__", "foo")), false, false, P));
        assertPrints("foo anchored", VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                List.of(eq("__name__", "foo")), true, false, P));
        assertPrints("foo smoothed", VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                List.of(eq("__name__", "foo")), false, true, P));
        assertPrints("foo @ 100.000 anchored offset 5m", VectorSelector.of("foo",
                300_000_000_000L, null, 100_000L, StartOrEnd.NONE,
                List.of(eq("__name__", "foo")), true, false, P));
    }

    // ------------------------------------------------------------------
    // 二元 / 一元 / 括号
    // ------------------------------------------------------------------

    @Test
    @DisplayName("二元表达式：on/ignoring/group_left/right、集合算子、标量、bool")
    void binaryExprs() {
        VectorSelector a = vs("a", eq("__name__", "a"));
        VectorSelector b = vs("b", eq("__name__", "b"));
        assertPrints("a + b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of(), false, List.of(), null, null), false));
        assertPrints("a + on (x) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of("x"), true, List.of(), null, null), false));
        assertPrints("a + ignoring (x) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of("x"), false, List.of(), null, null), false));
        assertPrints("a + on (x, y) group_left (z) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.MANY_TO_ONE,
                        List.of("x", "y"), true, List.of("z"), null, null), false));
        assertPrints("a + on (x) group_right (z) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_MANY,
                        List.of("x"), true, List.of("z"), null, null), false));
        // group_left 无 include 标签 → 保留空括号
        assertPrints("a + on (x) group_left () b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.MANY_TO_ONE,
                        List.of("x"), true, List.of(), null, null), false));
        // 集合算子
        assertPrints("a and b", BinaryExpr.of(BinaryOp.LAND, a, b,
                new VectorMatching(VectorMatchCardinality.MANY_TO_MANY,
                        List.of(), false, List.of(), null, null), false));
        // 纯标量：vectorMatching 为 null
        assertPrints("1 + 2", BinaryExpr.of(BinaryOp.ADD, num(1), num(2), null, false));
        // bool 修饰符
        assertPrints("1 == bool 2", BinaryExpr.of(BinaryOp.EQLC, num(1), num(2), null, true));
    }

    @Test
    @DisplayName("fill 修饰符：fill/fill_left/fill_right 与双侧不等")
    void fillModifiers() {
        VectorSelector a = vs("a", eq("__name__", "a"));
        VectorSelector b = vs("b", eq("__name__", "b"));
        // 两侧 fill 相等 → 收敛为 fill
        assertPrints("a + fill (-23) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of(), false, List.of(), -23.0, -23.0), false));
        // 仅左侧
        assertPrints("a + fill_left (-23) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of(), false, List.of(), -23.0, null), false));
        // 仅右侧
        assertPrints("a + fill_right (42) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of(), false, List.of(), null, 42.0), false));
        // 两侧不等 → 两个修饰符
        assertPrints("a + fill_left (-23) fill_right (42) b", BinaryExpr.of(BinaryOp.ADD, a, b,
                new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                        List.of(), false, List.of(), -23.0, 42.0), false));
    }

    @Test
    @DisplayName("括号与一元：-foo、-(1)、+foo")
    void parenAndUnary() {
        assertPrints("(1)", ParenExpr.of(num(1), P));
        assertPrints("-foo", UnaryExpr.of(UnaryOp.MINUS,
                vs("foo", eq("__name__", "foo")), 0));
        assertPrints("-(1)", UnaryExpr.of(UnaryOp.MINUS, ParenExpr.of(num(1), P), 0));
        assertPrints("+foo", UnaryExpr.of(UnaryOp.PLUS,
                vs("foo", eq("__name__", "foo")), 0));
    }

    // ------------------------------------------------------------------
    // 聚合 / 调用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("聚合：by/without 空分组、带参聚合、非 legacy 分组标签引号")
    void aggregates() {
        VectorSelector x = vs("x", eq("__name__", "x"));
        assertPrints("sum(x)", AggregateExpr.of(AggregateOp.SUM, x, null, List.of(), false, P));
        assertPrints("sum by (a, b) (x)", AggregateExpr.of(AggregateOp.SUM, x,
                null, List.of("a", "b"), false, P));
        // without() 空分组 → 保留空括号
        assertPrints("sum without () (x)", AggregateExpr.of(AggregateOp.SUM, x,
                null, List.of(), true, P));
        assertPrints("sum without (a) (x)", AggregateExpr.of(AggregateOp.SUM, x,
                null, List.of("a"), true, P));
        assertPrints("topk(3, x)", AggregateExpr.of(AggregateOp.TOPK, x,
                num(3), List.of(), false, P));
        assertPrints("count_values(\"v\", x)", AggregateExpr.of(AggregateOp.COUNT_VALUES, x,
                StringLiteral.of("v", P), List.of(), false, P));
        // 非 legacy 分组标签加引号（打印侧判定，保证输出可回读）
        assertPrints("sum by (\"foo bar\") (x)", AggregateExpr.of(AggregateOp.SUM, x,
                null, List.of("foo bar"), false, P));
        assertPrints("sum by (\"üüü\") (x)", AggregateExpr.of(AggregateOp.SUM, x,
                null, List.of("üüü"), false, P));
    }

    @Test
    @DisplayName("函数调用：rate/clamp/histogram_count")
    void calls() {
        assertPrints("rate(foo[5m])", Call.of(Functions.getFunction("rate"), List.of(
                MatrixSelector.of(vs("foo", eq("__name__", "foo")),
                        300_000_000_000L, null, 0)), P));
        assertPrints("clamp(v, 1, 2)", Call.of(Functions.getFunction("clamp"), List.of(
                vs("v", eq("__name__", "v")), num(1), num(2)), P));
        assertPrints("histogram_count(foo)", Call.of(
                Functions.getFunction("histogram_count"), List.of(
                        vs("foo", eq("__name__", "foo"))), P));
    }

    // ------------------------------------------------------------------
    // 矩阵选择器 / 子查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("矩阵选择器：[range] → anchored → @ → offset 顺序")
    void matrixSelectors() {
        // 顺序：[range] → anchored/smoothed → @ → offset（与裸向量选择器的
        // @→anchored 顺序不同，见 Go printer.go atOffset）
        assertPrints("foo[5m]", MatrixSelector.of(
                vs("foo", eq("__name__", "foo")), 300_000_000_000L, null, 0));
        assertPrints("a{b=\"c\"}[5m]", MatrixSelector.of(
                vs("a", eq("b", "c")), 300_000_000_000L, null, 0));
        assertPrints("foo[5m] anchored", MatrixSelector.of(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), true, false, P),
                300_000_000_000L, null, 0));
        assertPrints("foo[5m] smoothed", MatrixSelector.of(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, true, P),
                300_000_000_000L, null, 0));
        assertPrints("foo[5m] anchored @ start() offset 1m", MatrixSelector.of(
                VectorSelector.of("foo", 60_000_000_000L, null, null, StartOrEnd.START,
                        List.of(eq("__name__", "foo")), true, false, P),
                300_000_000_000L, null, 0));
        assertPrints("foo[5m] offset 1m", MatrixSelector.of(
                VectorSelector.of("foo", 60_000_000_000L, null, null, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P),
                300_000_000_000L, null, 0));
        assertPrints("foo[5m] @ 10.000", MatrixSelector.of(
                VectorSelector.of("foo", 0L, null, 10_000L, StartOrEnd.NONE,
                        List.of(eq("__name__", "foo")), false, false, P),
                300_000_000_000L, null, 0));
        // 时长表达式区间
        assertPrints("foo[2 * 3m]", MatrixSelector.of(
                vs("foo", eq("__name__", "foo")), 0L,
                DurationExpr.of(DurationOp.MUL, num(2), dur(180), false, 0, 0), 0));
    }

    @Test
    @DisplayName("子查询：range:step、空步长、wrapped 括号、内嵌表达式、@+offset、step()")
    void subqueries() {
        assertPrints("foo[5m:1m]", SubqueryExpr.of(
                vs("foo", eq("__name__", "foo")),
                300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                60_000_000_000L, null, 0));
        // step 为 0 且无表达式 → 空
        assertPrints("foo[5m:]", SubqueryExpr.of(
                vs("foo", eq("__name__", "foo")),
                300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                0L, null, 0));
        // 区间与步长都是 wrapped 时长表达式 → 各自带括号
        assertPrints("foo[(5m):(1m)]", SubqueryExpr.of(
                vs("foo", eq("__name__", "foo")),
                0L, DurationExpr.of(DurationOp.ADD, null, dur(300), true, 0, 0),
                0L, null, null, StartOrEnd.NONE,
                0L, DurationExpr.of(DurationOp.ADD, null, dur(60), true, 0, 0), 0));
        // 内嵌任意表达式 + @ + offset
        assertPrints("rate(foo[5m])[30m:1m]", SubqueryExpr.of(
                Call.of(Functions.getFunction("rate"), List.of(MatrixSelector.of(
                        vs("foo", eq("__name__", "foo")), 300_000_000_000L, null, 0)), P),
                1_800_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                60_000_000_000L, null, 0));
        assertPrints("foo[5m:1m] @ 1600.000 offset 5m", SubqueryExpr.of(
                vs("foo", eq("__name__", "foo")),
                300_000_000_000L, null, 300_000_000_000L, null,
                1_600_000L, StartOrEnd.NONE,
                60_000_000_000L, null, 0));
        assertPrints("foo[5m:step()]", SubqueryExpr.of(
                vs("foo", eq("__name__", "foo")),
                300_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                0L, step(), 0));
    }

    // ------------------------------------------------------------------
    // 时长表达式
    // ------------------------------------------------------------------

    @Test
    @DisplayName("时长表达式：step/range、二元空格、一元、wrapped 括号、复合")
    void durationExprs() {
        assertPrints("step()", step());
        assertPrints("range()", DurationExpr.of(DurationOp.RANGE, null, null, false, 0, 0));
        // 二元：操作符两侧带空格
        assertPrints("2 * 3m", DurationExpr.of(DurationOp.MUL, num(2), dur(180), false, 0, 0));
        assertPrints("min_of(1m, 30s)", DurationExpr.of(DurationOp.MIN_OF,
                dur(60), dur(30), false, 0, 0));
        assertPrints("max_of(1m, 30s)", DurationExpr.of(DurationOp.MAX_OF,
                dur(60), dur(30), false, 0, 0));
        // 一元：负号直接前缀、无括号；正号被丢弃
        assertPrints("-step()", DurationExpr.of(DurationOp.SUB, null, step(), false, 0, 0));
        assertPrints("step()", DurationExpr.of(DurationOp.ADD, null, step(), false, 0, 0));
        // wrapped → 括号
        assertPrints("(10s - 5s)", DurationExpr.of(DurationOp.SUB,
                dur(10), dur(5), true, 0, 0));
        assertPrints("-(10s - 5s)", DurationExpr.of(DurationOp.SUB, null,
                DurationExpr.of(DurationOp.SUB, dur(10), dur(5), true, 0, 0), false, 0, 0));
        // 复合：200 - min_of(-step() ^ step(), 1)
        DurationExpr negPow = DurationExpr.of(DurationOp.SUB, null,
                DurationExpr.of(DurationOp.POW, step(), step(), false, 0, 0), false, 0, 0);
        assertPrints("200 - min_of(-step() ^ step(), 1)", DurationExpr.of(DurationOp.SUB,
                num(200), DurationExpr.of(DurationOp.MIN_OF, negPow, num(1), false, 0, 0),
                false, 0, 0));
    }
}

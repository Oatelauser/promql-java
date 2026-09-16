package io.github.oatelauser.promql.parser;

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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 抽样 AST 一致性测试：手工构造期望 AST 与解析结果<b>逐字段</b>比较
 * （利用 Q9 决定——record 的 equals/hashCode 忽略位置字段），另对少量
 * 位置区间显式断言。
 *
 * <p>用例取自 {@code parse_test.go} testExpr 表中有代表性的 expected AST
 * （覆盖全部节点类型：字面量/选择器/矩阵/子查询/一元/二元/聚合/调用/
 * 时长表达式/anchored/smoothed），与 {@link ParserConformanceTest}
 * 的“全量解析 + 打印幂等”互补。
 *
 * <p>时长字段口径（.y 664-672 行）：字面量区间填 {@code range}（纳秒）；
 * 时长<b>表达式</b>区间（如 {@code foo[2*3m]}）只填 {@code rangeExpr}，
 * {@code range} 保持 0——求值属引擎期，不在解析期。
 */
@DisplayName("官方抽样：AST 逐字段一致性")
class ParserAstConformanceTest {

    /** 占位位置（equals 忽略位置字段，仅少数显式断言用真实区间）。 */
    private static final PositionRange P = new PositionRange(0, 0);

    private static final ParserOptions OPTS = new ParserOptions(true, true, true, true);

    private static Expr parse(String q) {
        return Parser.parseExpr(q, OPTS);
    }

    private static VectorSelector vs(String name, LabelMatcher... ms) {
        return VectorSelector.of(name, 0L, null, null, StartOrEnd.NONE, List.of(ms), false, false, P);
    }

    // ------------------------------------------------------------------
    // 字面量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数字字面量：一元负号折叠进字面量")
    void numberLiteral() {
        assertEquals(NumberLiteral.of(1, false, P), parse("1"));
        assertEquals(NumberLiteral.of(-1.5, false, P), parse("-1.5"));
        // 一元负号折叠进字面量（.y 731-736：nl.Val *= -1）
        assertEquals(NumberLiteral.of(-1, false, P), parse("-1"));
    }

    @Test
    @DisplayName("时长字面量：5m → 300 秒并带 duration 标记")
    void durationLiteral() {
        // 5m = 300 秒，duration 标记为 true
        assertEquals(NumberLiteral.of(300, true, P), parse("5m"));
    }

    @Test
    @DisplayName("字符串字面量：双引号内容")
    void stringLiteral() {
        assertEquals(StringLiteral.of("foo bar", P), parse("\"foo bar\""));
    }

    // ------------------------------------------------------------------
    // 向量选择器与匹配器
    // ------------------------------------------------------------------

    @Test
    @DisplayName("裸向量选择器：自动追加 __name__ 匹配器")
    void bareVectorSelector() {
        assertEquals(vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")), parse("foo"));
    }

    @Test
    @DisplayName("匹配器保持源顺序，__name__ 追加在末尾")
    void labelMatchersInSourceOrder() {
        // Q9：匹配器保持源顺序，__name__ 追加在末尾
        assertEquals(
                vs("foo",
                        new LabelMatcher("a", MatchType.EQUAL, "1"),
                        new LabelMatcher("b", MatchType.EQUAL, "2"),
                        new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                parse("foo{a=\"1\",b=\"2\"}"));
    }

    @Test
    @DisplayName("四种匹配类型（= != =~ !~）")
    void matchTypes() {
        assertEquals(
                VectorSelector.of("", 0L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("foo", MatchType.REGEXP, ".+")), false, false, P),
                parse("{foo=~\".+\"}"));
        assertEquals(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("a", MatchType.NOT_EQUAL, "1"),
                                new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, false, P),
                parse("foo{a!=\"1\"}"));
        // 注意：{foo!="bar"} 在 Go 中是 fail 用例——NOT_EQUAL 且值非空反而匹配空串，
        // 整个选择器没有非空匹配器（此处用带指标名的形式规避）。
        assertEquals(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("a", MatchType.NOT_REGEXP, ".+"),
                                new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, false, P),
                parse("foo{a!~\".+\"}"));
        // 注意：{foo!~".+"} 单独在 Go 中是 fail 用例——NOT_REGEXP 对空串取反后
        // 仍匹配空串（Matches("")=!re("")=true），需靠指标名等非空匹配器兜底。
    }

    @Test
    @DisplayName("offset 与 @ 修饰符（毫秒时间戳 / start() / end()）")
    void offsetAndAt() {
        assertEquals(
                VectorSelector.of("foo", 300_000_000_000L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, false, P),
                parse("foo offset 5m"));
        // @ 100 → 毫秒时间戳 100_000
        assertEquals(
                VectorSelector.of("foo", 0L, null, 100_000L, StartOrEnd.NONE,
                        List.of(new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, false, P),
                parse("foo @ 100"));
        assertEquals(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.END,
                        List.of(new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, false, P),
                parse("foo @ end()"));
    }

    @Test
    @DisplayName("anchored / smoothed 实验修饰符")
    void anchoredSmoothed() {
        assertEquals(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("__name__", MatchType.EQUAL, "foo")), true, false, P),
                parse("foo anchored"));
        assertEquals(
                VectorSelector.of("foo", 0L, null, null, StartOrEnd.NONE,
                        List.of(new LabelMatcher("__name__", MatchType.EQUAL, "foo")), false, true, P),
                parse("foo smoothed"));
    }

    // ------------------------------------------------------------------
    // 矩阵选择器 / 子查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("矩阵选择器：字面量区间填 range 纳秒")
    void matrixSelectorLiteralRange() {
        assertEquals(
                MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        300_000_000_000L, null, 0),
                parse("foo[5m]"));
    }

    @Test
    @DisplayName("矩阵选择器：时长表达式区间（range=0 只填 rangeExpr）")
    void matrixSelectorDurationExprRange() {
        // 时长表达式区间：range=0，只填 rangeExpr（.y 664-672）
        assertEquals(
                MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        0L, DurationExpr.of(DurationOp.MUL,
                                NumberLiteral.of(2, false, P), NumberLiteral.of(180, true, P),
                                false, 0, 0), 0),
                parse("foo[2*3m]"));
    }

    @Test
    @DisplayName("子查询：步长与空步长")
    void subqueryStepAndEmptyStep() {
        assertEquals(
                SubqueryExpr.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        600_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        60_000_000_000L, null, 0),
                parse("foo[10m:1m]"));
        assertEquals(
                SubqueryExpr.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        600_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        0L, null, 0),
                parse("foo[10m:]"));
    }

    @Test
    @DisplayName("子查询：@ 与 offset 组合")
    void subqueryWithAtAndOffset() {
        assertEquals(
                SubqueryExpr.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        600_000_000_000L, null, 300_000_000_000L, null,
                        1_600_000L, StartOrEnd.NONE,
                        60_000_000_000L, null, 0),
                parse("foo[10m:1m] @ 1600 offset 5m"));
    }

    @Test
    @DisplayName("子查询：step() 步长表达式")
    void subqueryStepExpr() {
        assertEquals(
                SubqueryExpr.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        600_000_000_000L, null, 0L, null, null, StartOrEnd.NONE,
                        0L, DurationExpr.of(DurationOp.STEP, null, null, false, 0, 0), 0),
                parse("foo[10m:step()]"));
    }

    @Test
    @DisplayName("时长表达式：step() 区间 / min_of / 一元+wrapped")
    void durationExprForms() {
        // step() 区间
        assertEquals(
                MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        0L, DurationExpr.of(DurationOp.STEP, null, null, false, 0, 0), 0),
                parse("foo[step()]"));
        // min_of(1m, 30s)
        assertEquals(
                MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        0L, DurationExpr.of(DurationOp.MIN_OF,
                                NumberLiteral.of(60, true, P), NumberLiteral.of(30, true, P),
                                false, 0, 0), 0),
                parse("foo[min_of(1m, 30s)]"));
        // -(10s-5s)+20s：一元 SUB 包住 wrapped 的二元减法
        assertEquals(
                MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        0L, DurationExpr.of(DurationOp.ADD,
                                DurationExpr.of(DurationOp.SUB, null,
                                        DurationExpr.of(DurationOp.SUB,
                                                NumberLiteral.of(10, true, P), NumberLiteral.of(5, true, P),
                                                true, 0, 0),
                                        false, 0, 0),
                                NumberLiteral.of(20, true, P), false, 0, 0), 0),
                parse("foo[-(10s-5s)+20s]"));
    }

    // ------------------------------------------------------------------
    // 一元 / 括号 / 二元
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一元与括号：一元负号不穿透括号折叠")
    void unaryAndParen() {
        assertEquals(UnaryExpr.of(UnaryOp.MINUS, vs("foo",
                new LabelMatcher("__name__", MatchType.EQUAL, "foo")), 0), parse("-foo"));
        // 一元不穿透括号折叠
        assertEquals(UnaryExpr.of(UnaryOp.MINUS,
                ParenExpr.of(NumberLiteral.of(1, false, P), P), 0), parse("-(1)"));
    }

    @Test
    @DisplayName("二元优先级与结合性（POW 右结合、括号覆盖优先级）")
    void binaryPrecedenceAndAssociativity() {
        // 1 + 2 * 3 → ADD(1, MUL(2,3))；纯标量 → vectorMatching 置空
        assertEquals(
                BinaryExpr.of(BinaryOp.ADD,
                        NumberLiteral.of(1, false, P),
                        BinaryExpr.of(BinaryOp.MUL, NumberLiteral.of(2, false, P),
                                NumberLiteral.of(3, false, P), null, false),
                        null, false),
                parse("1 + 2 * 3"));
        // 2 ^ 3 ^ 2 → 右结合
        assertEquals(
                BinaryExpr.of(BinaryOp.POW, NumberLiteral.of(2, false, P),
                        BinaryExpr.of(BinaryOp.POW, NumberLiteral.of(3, false, P),
                                NumberLiteral.of(2, false, P), null, false),
                        null, false),
                parse("2 ^ 3 ^ 2"));
        // (1 + 2) * 3 → 括号覆盖优先级
        assertEquals(
                BinaryExpr.of(BinaryOp.MUL,
                        ParenExpr.of(BinaryExpr.of(BinaryOp.ADD,
                                NumberLiteral.of(1, false, P), NumberLiteral.of(2, false, P),
                                null, false), P),
                        NumberLiteral.of(3, false, P), null, false),
                parse("(1 + 2) * 3"));
    }

    @Test
    @DisplayName("二元向量匹配：one-to-one / group_left / 集合算子")
    void binaryVectorMatching() {
        // 向量 + 向量：one-to-one、无 matching/include
        assertEquals(
                BinaryExpr.of(BinaryOp.ADD, vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        vs("bar", new LabelMatcher("__name__", MatchType.EQUAL, "bar")),
                        new VectorMatching(VectorMatchCardinality.ONE_TO_ONE,
                                List.of(), false, List.of(), null, null), false),
                parse("foo + bar"));
        // group_left：many-to-one + include
        assertEquals(
                BinaryExpr.of(BinaryOp.ADD, vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        vs("bar", new LabelMatcher("__name__", MatchType.EQUAL, "bar")),
                        new VectorMatching(VectorMatchCardinality.MANY_TO_ONE,
                                List.of("x", "y"), true, List.of("l"), null, null), false),
                parse("foo + on(x, y) group_left(l) bar"));
        // 集合算子：many-to-many
        assertEquals(
                BinaryExpr.of(BinaryOp.LAND, vs("a", new LabelMatcher("__name__", MatchType.EQUAL, "a")),
                        vs("b", new LabelMatcher("__name__", MatchType.EQUAL, "b")),
                        new VectorMatching(VectorMatchCardinality.MANY_TO_MANY,
                                List.of("x"), true, List.of(), null, null), false),
                parse("a and on(x) b"));
    }

    @Test
    @DisplayName("比较算子 bool 修饰符")
    void comparisonBoolModifier() {
        assertEquals(
                BinaryExpr.of(BinaryOp.EQLC, NumberLiteral.of(1, false, P),
                        NumberLiteral.of(2, false, P), null, true),
                parse("1 == bool 2"));
    }

    // ------------------------------------------------------------------
    // 聚合 / 函数调用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("聚合：sum / topk / by / without")
    void aggregations() {
        assertEquals(
                AggregateExpr.of(AggregateOp.SUM,
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        null, List.of(), false, P),
                parse("sum(foo)"));
        assertEquals(
                AggregateExpr.of(AggregateOp.SUM,
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        null, List.of("x", "y"), false, P),
                parse("sum by (x, y) (foo)"));
        assertEquals(
                AggregateExpr.of(AggregateOp.SUM,
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        null, List.of("x"), true, P),
                parse("sum without (x) (foo)"));
        assertEquals(
                AggregateExpr.of(AggregateOp.TOPK,
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        NumberLiteral.of(3, false, P), List.of(), false, P),
                parse("topk(3, foo)"));
    }

    @Test
    @DisplayName("分组标签支持字符串（UTF-8 方案）")
    void groupingWithStringLabelName() {
        // 分组标签支持字符串（UTF-8 方案）
        assertEquals(
                AggregateExpr.of(AggregateOp.SUM,
                        vs("x", new LabelMatcher("__name__", MatchType.EQUAL, "x")),
                        null, List.of("foo bar"), false, P),
                parse("sum by (\"foo bar\") (x)"));
    }

    @Test
    @DisplayName("函数调用：rate / clamp")
    void functionCall() {
        assertEquals(
                Call.of(Functions.getFunction("rate"), List.of(MatrixSelector.of(
                        vs("foo", new LabelMatcher("__name__", MatchType.EQUAL, "foo")),
                        300_000_000_000L, null, 0)), P),
                parse("rate(foo[5m])"));
        assertEquals(
                Call.of(Functions.getFunction("clamp"), List.of(
                        vs("v", new LabelMatcher("__name__", MatchType.EQUAL, "v")),
                        NumberLiteral.of(1, false, P),
                        NumberLiteral.of(2, false, P)), P),
                parse("clamp(v, 1, 2)"));
    }

    // ------------------------------------------------------------------
    // 位置区间抽样（UTF-16 代码单元口径，与 Go 字节偏移在 ASCII 输入上一致）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("位置区间抽样（UTF-16 代码单元口径）")
    void sampledPositionRanges() {
        Expr e = parse("foo + bar");
        assertEquals(0, e.positionRange().start());
        assertEquals(9, e.positionRange().end());
        e = parse("rate(foo[5m])");
        assertEquals(0, e.positionRange().start());
        assertEquals(13, e.positionRange().end());
        // offset 修饰符扩展选择器区间至末尾（lastClosing）：f0..o2 ␣3 offset4-9 ␣10 5m11-12 → end=13
        e = parse("foo offset 5m");
        assertEquals(0, e.positionRange().start());
        assertEquals(13, e.positionRange().end());
    }
}

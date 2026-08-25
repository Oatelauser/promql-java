package com.promql.parser;

import com.promql.ast.AggregateExpr;
import com.promql.ast.AggregateOp;
import com.promql.ast.BinaryExpr;
import com.promql.ast.BinaryOp;
import com.promql.ast.Call;
import com.promql.ast.DurationExpr;
import com.promql.ast.DurationOp;
import com.promql.ast.Expr;
import com.promql.ast.MatrixSelector;
import com.promql.ast.NumberLiteral;
import com.promql.ast.ParenExpr;
import com.promql.ast.StartOrEnd;
import com.promql.ast.StringLiteral;
import com.promql.ast.SubqueryExpr;
import com.promql.ast.UnaryExpr;
import com.promql.ast.UnaryOp;
import com.promql.ast.VectorMatchCardinality;
import com.promql.ast.VectorMatching;
import com.promql.ast.VectorSelector;
import com.promql.functions.Function;
import com.promql.functions.Functions;
import com.promql.labels.Label;
import com.promql.labels.LabelMatcher;
import com.promql.labels.Labels;
import com.promql.labels.MatchType;
import com.promql.lexer.Item;
import com.promql.lexer.ItemType;
import com.promql.lexer.Lexer;
import com.promql.posrange.PositionRange;
import com.promql.util.DurationFormat;
import com.promql.util.GoFloat;
import com.promql.util.GoStrings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * PromQL 递归下降解析器，逐行移植自 Prometheus 官方 Go 实现
 * {@code docs/promql/parser/parse.go} 与 {@code generated_parser.y}。
 *
 * <p>Go 原版由 yacc 生成；本移植以“优先级爬升”手工复刻其文法与语义：
 * 优先级表（{@code generated_parser.y} 204-217 行）为
 * {@code LOR < LAND/LUNLESS < 比较 < 加减 < 乘除模/atan2 < 幂(右结合)}，
 * {@code OFFSET} 无结合性、{@code LEFT_BRACKET} 右结合；一元算子带
 * {@code %prec MUL}（即 {@code -2*3 == (-2)*3} 而 {@code -2^2 == -(2^2)}）。
 *
 * <p>与 Go 版的主要结构性差异（均为不可变 AST 适配，语义等价）：
 * <ul>
 *   <li>Go 的“建后改写”（{@code addOffset}、{@code setTimestamp}、
 *       {@code nl.Val *= -1}、checkAST 的 {@code VectorMatching} 置空/改
 *       Cardinality）改为“重建”（with 风格）；checkAST 因此返回
 *       （可能重建过的）节点，由父节点回接。</li>
 *   <li>Go 在语法错误后继续扫描以累积多个错误；本版在首个语法错误处中止
 *       （对外仍抛出携带 {@link ParseError} 列表的 {@link PromqlParseException}，
 *       消息与 Go 首错一致）。checkAST 与 experimentalDurationExpr 等语义
 *       检查仍为累积式，与 Go 相同。</li>
 *   <li>{@code InjectItem} 起始符号注入机制裁剪：三个入口直接对应三个起始符号。</li>
 * </ul>
 *
 * <p>实例非线程安全（持有词法状态）；静态入口方法线程安全。
 */
public final class Parser {

    /** 二元运算符优先级（对应 .y 文件 204-210 行，值越大结合越紧）。 */
    private static final int PREC_LOR = 1;
    private static final int PREC_LAND = 2;
    private static final int PREC_CMP = 3;
    private static final int PREC_ADDSUB = 4;
    private static final int PREC_MULDIV = 5;
    private static final int PREC_POW = 6;

    /**
     * durationLiteralOutOfRange 的界（Go {@code 1<<63/1e9}：无类型常量
     * {@code 1<<63} 为 +9.223372036854776e18，Java 中 {@code (double)(1L<<63)}
     * 为负，须直接写字面量）。
     */
    private static final double MAX_DURATION_LITERAL_SECONDS = 9.223372036854776E18 / 1e9;

    /** 解析中止信号：首个语法错误已入列，立即上抛。 */
    private static final class Abort extends RuntimeException {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }

    private final Lexer lex;
    private final String input;
    private final ParserOptions options;
    /** 当前向前看词法单元（Go {@code p.yyParser.lval.item}）。 */
    private Item item;
    private final List<ParseError> parseErrors = new ArrayList<>();
    /** 最近一个收尾定界符（右括号/右花括号/右方括号/时长/数字）之后的下标。 */
    private int lastClosing;
    /** {@code info()} 第二参数的向量选择器：跳过“非空匹配器”检查（Go 用节点上的 BypassEmptyMatcherCheck 标志）。 */
    private final Set<VectorSelector> infoBypass = Collections.newSetFromMap(new IdentityHashMap<>());

    private Parser(String input, ParserOptions options) {
        this.lex = new Lexer(input);
        this.input = input;
        this.options = options;
    }

    // ====================================================================
    // 入口（对应 Go ParseExpr / ParseMetricSelector / ParseMetric）
    // ====================================================================

    /**
     * 解析完整 PromQL 表达式（起始符号 START_EXPRESSION）。
     *
     * @throws PromqlParseException 输入不合法（携带全部已累积的 {@link ParseError}）
     */
    public static Expr parseExpr(String input) {
        return parseExpr(input, ParserOptions.defaults());
    }

    /** 同 {@link #parseExpr(String)}，可指定实验特性开关。 */
    public static Expr parseExpr(String input, ParserOptions options) {
        Parser p = new Parser(input, options);
        try {
            Expr e = p.runExpr();
            p.throwIfErrors();
            return e;
        } catch (Abort a) {
            throw new PromqlParseException(p.parseErrors);
        }
    }

    /** 解析指标选择器（起始符号 START_METRIC_SELECTOR），返回含 {@code __name__} 匹配器的列表。 */
    public static List<LabelMatcher> parseMetricSelector(String input) {
        return parseMetricSelector(input, ParserOptions.defaults());
    }

    /** 同 {@link #parseMetricSelector(String)}，可指定实验特性开关。 */
    public static List<LabelMatcher> parseMetricSelector(String input, ParserOptions options) {
        Parser p = new Parser(input, options);
        try {
            p.advance();
            VectorSelector vs = p.parseVectorSelector();
            if (p.item.typ() != ItemType.EOF) {
                p.unexpected("", "");
            }
            p.throwIfErrors();
            return vs.labelMatchers();
        } catch (Abort a) {
            throw new PromqlParseException(p.parseErrors);
        }
    }

    /** 解析指标描述（起始符号 START_METRIC），返回按名排序的标签列表。 */
    public static List<Label> parseMetric(String input) {
        return parseMetric(input, ParserOptions.defaults());
    }

    /** 同 {@link #parseMetric(String)}，可指定实验特性开关。 */
    public static List<Label> parseMetric(String input, ParserOptions options) {
        Parser p = new Parser(input, options);
        try {
            p.advance();
            String name = null;
            if (isMetricIdentifier(p.item.typ())) {
                name = p.item.val();
                p.advance();
            }
            List<Label> labels = p.parseLabelSet();
            if (p.item.typ() != ItemType.EOF) {
                p.unexpected("", "");
            }
            p.throwIfErrors();
            return name != null ? Labels.withMetricName(labels, name) : labels;
        } catch (Abort a) {
            throw new PromqlParseException(p.parseErrors);
        }
    }

    /** 顶层解析（START_EXPRESSION：expr 后必须紧跟 EOF）。 */
    private Expr runExpr() {
        advance();
        if (item.typ() == ItemType.EOF) {
            // Go 用零值 PositionRange{0,0} 报此错（非 undefined）。
            fail(new PositionRange(0, 0), "no expression found in input");
        }
        Expr e = parseBinary(PREC_LOR);
        if (item.typ() != ItemType.EOF) {
            unexpected("", "");
        }
        throwIfErrors();
        e = checkAST(e);
        throwIfErrors();
        return e;
    }

    private void throwIfErrors() {
        if (!parseErrors.isEmpty()) {
            throw Abort.INSTANCE;
        }
    }

    // ====================================================================
    // 词法单元推进与错误报告
    // ====================================================================

    /**
     * 取下一个词法单元（跳过注释），对应 Go {@code parser.Lex}：
     * ERROR 词法单元以词法器起点..输入末尾为区间直接报告；右括号/右花括号/
     * 右方括号/时长/数字更新 lastClosing。
     */
    private void advance() {
        while (true) {
            item = lex.nextItem();
            if (item.typ() != ItemType.COMMENT) {
                break;
            }
        }
        if (item.typ() == ItemType.ERROR) {
            addParseErrf(new PositionRange(lex.start(), input.length()), item.val());
            throw Abort.INSTANCE;
        }
        switch (item.typ()) {
            case RIGHT_BRACE, RIGHT_PAREN, RIGHT_BRACKET, DURATION, NUMBER ->
                    lastClosing = item.pos() + item.val().length();
            default -> {
            }
        }
    }

    /** Go {@code Item.PositionRange()}。 */
    private static PositionRange range(Item i) {
        return new PositionRange(i.pos(), i.pos() + i.val().length());
    }

    /** Go {@code mergeRanges(&a, &b)}（Item 版）。 */
    private static PositionRange itemMerge(Item a, Item b) {
        return new PositionRange(a.pos(), b.pos() + b.val().length());
    }

    /** 累积一个错误（不中止），checkAST / experimentalDurationExpr 使用。 */
    private void addParseErrf(PositionRange r, String msg) {
        parseErrors.add(new ParseError(r, msg, input, 0));
    }

    /** 累积错误并中止解析（语法错误路径统一入口）。 */
    private void fail(PositionRange r, String msg) {
        addParseErrf(r, msg);
        throw Abort.INSTANCE;
    }

    /**
     * Go {@code parser.unexpected}：消息为
     * {@code unexpected <desc>[ in <context>][, expected <expected>]}；
     * 词法 ERROR 不重复报告。
     */
    private void unexpected(String context, String expected) {
        if (item.typ() == ItemType.ERROR) {
            throw Abort.INSTANCE;
        }
        StringBuilder sb = new StringBuilder("unexpected ").append(item.desc());
        if (!context.isEmpty()) {
            sb.append(" in ").append(context);
        }
        if (!expected.isEmpty()) {
            sb.append(", expected ").append(expected);
        }
        fail(range(item), sb.toString());
    }

    // ====================================================================
    // 二元表达式（优先级爬升）
    // ====================================================================

    /** 二元修饰符（bin_modifier 规则的解析结果）。 */
    private static final class BinMods {
        boolean returnBool;
        List<String> matchingLabels;
        boolean on;
        VectorMatchCardinality card = VectorMatchCardinality.ONE_TO_ONE;
        List<String> include;
        Double fillLhs;
        Double fillRhs;
    }

    /** 解析二元表达式：一元/初等后按优先级爬升结合运算符（左结合；幂右结合）。 */
    private Expr parseBinary(int minPrec) {
        Expr lhs = parseUnaryExpr();
        while (true) {
            BinaryOp op = binaryOpOf(item.typ());
            if (op == null) {
                break;
            }
            int prec = binaryPrec(item.typ());
            if (prec < minPrec) {
                break;
            }
            Item opItem = item;
            advance();
            BinMods mods = parseBinModifiers();
            Expr rhs = prec == PREC_POW ? parseBinary(prec) : parseBinary(prec + 1);
            lhs = newBinaryExpression(lhs, opItem, op, mods, rhs);
        }
        return lhs;
    }

    /** bin_modifier 规则：[BOOL] [ON/IGNORING(标签)] [GROUP_LEFT/RIGHT [(标签)]] [fill 变体]。 */
    private BinMods parseBinModifiers() {
        BinMods m = new BinMods();
        if (item.typ() == ItemType.BOOL) {
            m.returnBool = true;
            advance();
        }
        if (item.typ() == ItemType.ON || item.typ() == ItemType.IGNORING) {
            m.on = item.typ() == ItemType.ON;
            advance();
            m.matchingLabels = parseGroupingLabels();
            if (item.typ() == ItemType.GROUP_LEFT) {
                advance();
                m.card = VectorMatchCardinality.MANY_TO_ONE;
                m.include = parseMaybeGroupingLabels();
            } else if (item.typ() == ItemType.GROUP_RIGHT) {
                advance();
                m.card = VectorMatchCardinality.ONE_TO_MANY;
                m.include = parseMaybeGroupingLabels();
            }
        }
        if (item.typ() == ItemType.FILL) {
            advance();
            double v = parseFillValue();
            m.fillLhs = v;
            m.fillRhs = v;
        } else if (item.typ() == ItemType.FILL_LEFT) {
            advance();
            m.fillLhs = parseFillValue();
            if (item.typ() == ItemType.FILL_RIGHT) {
                advance();
                m.fillRhs = parseFillValue();
            }
        } else if (item.typ() == ItemType.FILL_RIGHT) {
            advance();
            m.fillRhs = parseFillValue();
            if (item.typ() == ItemType.FILL_LEFT) {
                advance();
                m.fillLhs = parseFillValue();
            }
        }
        return m;
    }

    /** fill_value 规则：{@code ( 数字/时长 )} 或 {@code ( 一元 数字/时长 )}，返回数值。 */
    private double parseFillValue() {
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected("", "");
        }
        advance();
        boolean neg = false;
        if (item.typ() == ItemType.ADD || item.typ() == ItemType.SUB) {
            neg = item.typ() == ItemType.SUB;
            advance();
        }
        if (item.typ() != ItemType.NUMBER && item.typ() != ItemType.DURATION) {
            unexpected("", "");
        }
        Item num = item;
        advance();
        if (item.typ() != ItemType.RIGHT_PAREN) {
            unexpected("", "");
        }
        advance();
        double v = numberOrDurationSeconds(num);
        return neg ? -v : v;
    }

    /** Go {@code newBinaryExpression}：装配 + fill 实验开关检查。 */
    private BinaryExpr newBinaryExpression(Expr lhs, Item opItem, BinaryOp op, BinMods mods, Expr rhs) {
        if (!options.enableBinopFillModifiers() && (mods.fillLhs != null || mods.fillRhs != null)) {
            fail(new PositionRange(lhs.positionRange().start(), rhs.positionRange().end()),
                    "binop fill modifiers are experimental and not enabled");
        }
        VectorMatching vm = new VectorMatching(mods.card,
                mods.matchingLabels == null ? List.of() : mods.matchingLabels, mods.on,
                mods.include == null ? List.of() : mods.include,
                mods.fillLhs, mods.fillRhs);
        return BinaryExpr.of(op, lhs, rhs, vm, mods.returnBool);
    }

    // ====================================================================
    // 一元表达式（unary_op expr %prec MUL）
    // ====================================================================

    private Expr parseUnaryExpr() {
        if (item.typ() == ItemType.ADD || item.typ() == ItemType.SUB) {
            Item op = item;
            advance();
            // %prec MUL：操作数只在幂级（PREC_POW）内继续结合，
            // 故 -2*3 == (-2)*3、-2^2 == -(2^2)。
            Expr operand = parseBinary(PREC_POW);
            if (operand instanceof NumberLiteral nl) {
                double v = op.typ() == ItemType.SUB ? -nl.val() : nl.val();
                return NumberLiteral.of(v, nl.duration(),
                        new PositionRange(op.pos(), nl.positionRange().end()));
            }
            return UnaryExpr.of(op.typ() == ItemType.SUB ? UnaryOp.MINUS : UnaryOp.PLUS, operand, op.pos());
        }
        Expr e = parsePrimary();
        return parsePostfix(e);
    }

    // ====================================================================
    // 初等表达式
    // ====================================================================

    private Expr parsePrimary() {
        final ItemType t = item.typ();
        switch (t) {
            case NUMBER, DURATION:
                return numberDurationLiteral();
            case STRING: {
                Item s = item;
                String v = unquoteString(s.val());
                advance();
                return StringLiteral.of(v, range(s));
            }
            case LEFT_PAREN: {
                Item lp = item;
                advance();
                Expr inner = parseBinary(PREC_LOR);
                if (item.typ() != ItemType.RIGHT_PAREN) {
                    unexpected("", "");
                }
                Item rp = item;
                advance();
                return ParenExpr.of(inner, itemMerge(lp, rp));
            }
            case LEFT_BRACE:
                return parseVectorSelector();
            case IDENTIFIER: {
                Item ident = item;
                advance();
                if (item.typ() == ItemType.LEFT_PAREN) {
                    return parseFunctionCall(ident);
                }
                return vectorSelectorNamed(ident);
            }
            case METRIC_IDENTIFIER: {
                Item ident = item;
                advance();
                return vectorSelectorNamed(ident);
            }
            default: {
                if (t.isAggregator()) {
                    Item op = item;
                    advance();
                    if (item.typ() == ItemType.LEFT_PAREN || item.typ() == ItemType.BY
                            || item.typ() == ItemType.WITHOUT) {
                        return parseAggregate(op);
                    }
                    return vectorSelectorNamed(op);
                }
                if (t == ItemType.START || t == ItemType.END || t == ItemType.STEP || t == ItemType.RANGE
                        || t == ItemType.MAX_OF || t == ItemType.MIN_OF) {
                    Item kw = item;
                    advance();
                    if (item.typ() == ItemType.LEFT_PAREN) {
                        return parseFunctionCall(kw);
                    }
                    return vectorSelectorNamed(kw);
                }
                // metric_identifier 备选：OFFSET/ANCHORED/SMOOTHED/BY/LAND 等保留字
                // 亦可为指标名（.y 837 行），如 offset{step="1s"}[5m]。
                if (isMetricIdentifier(t)) {
                    Item n = item;
                    advance();
                    return vectorSelectorNamed(n);
                }
                unexpected("", "");
                return null;
            }
        }
    }

    /** 后缀修饰符循环：{@code [区间]/[区间:步长]/offset/@/anchored/smoothed}，可任意次叠加。 */
    private Expr parsePostfix(Expr e) {
        while (true) {
            switch (item.typ()) {
                case LEFT_BRACKET:
                    e = parseBracket(e);
                    break;
                case OFFSET: {
                    advance();
                    Expr d = parseOffsetDuration();
                    if (d instanceof NumberLiteral nl) {
                        e = applyOffset(e, Math.round(nl.val() * 1e9), null);
                    } else {
                        e = applyOffset(e, 0L, (DurationExpr) d);
                    }
                    break;
                }
                case AT: {
                    advance();
                    e = parseAtModifier(e);
                    break;
                }
                case ANCHORED: {
                    advance();
                    e = setAnchored(e);
                    break;
                }
                case SMOOTHED: {
                    advance();
                    e = setSmoothed(e);
                    break;
                }
                default:
                    return e;
            }
        }
    }

    // ====================================================================
    // 聚合表达式（aggregate_op [modifier] function_call_body [modifier]）
    // ====================================================================

    private AggregateExpr parseAggregate(Item op) {
        List<String> grouping = null;
        boolean without = false;
        boolean modifierFirst = false;
        if (item.typ() == ItemType.BY || item.typ() == ItemType.WITHOUT) {
            without = item.typ() == ItemType.WITHOUT;
            advance();
            grouping = parseGroupingLabels();
            modifierFirst = true;
        }
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected("aggregation", "");
        }
        List<Expr> args = parseFunctionCallArgs();
        // 无任何修饰符（aggregate_op function_call_body）时 overread=true：
        // 区间末尾需回溯到右括号（Go findPrevRightParen）。
        boolean overread = !modifierFirst;
        if (!modifierFirst && (item.typ() == ItemType.BY || item.typ() == ItemType.WITHOUT)) {
            without = item.typ() == ItemType.WITHOUT;
            advance();
            grouping = parseGroupingLabels();
            overread = false;
        }
        return newAggregateExpr(op, grouping, without, args, overread);
    }

    /** Go {@code newAggregateExpr}。 */
    private AggregateExpr newAggregateExpr(Item op, List<String> grouping, boolean without,
                                           List<Expr> args, boolean overread) {
        int end = lastClosing;
        if (overread) {
            end = lex.findPrevRightParen(lastClosing);
        }
        PositionRange posRange = new PositionRange(op.pos(), end);
        if (args.isEmpty()) {
            fail(posRange, "no arguments for aggregate expression provided");
        }
        int desiredArgs = 1;
        Expr param = null;
        if (op.typ().isAggregatorWithParam()) {
            if (!options.enableExperimentalFunctions() && op.typ().isExperimentalAggregator()) {
                fail(posRange, String.format(
                        "%s() is experimental and must be enabled with --enable-feature=promql-experimental-functions",
                        op.val()));
            }
            desiredArgs = 2;
            param = args.get(0);
        }
        if (args.size() != desiredArgs) {
            fail(posRange, String.format(
                    "wrong number of arguments for aggregate expression provided, expected %d, got %d",
                    desiredArgs, args.size()));
        }
        return AggregateExpr.of(toAggregateOp(op.typ()), args.get(desiredArgs - 1), param,
                grouping == null ? List.of() : grouping, without, posRange);
    }

    // ====================================================================
    // 函数调用
    // ====================================================================

    /** IDENTIFIER/STEP/RANGE/START/END/MAX_OF/MIN_OF 后跟 {@code (} 时的函数调用。 */
    private Call parseFunctionCall(Item ident) {
        List<Expr> args = parseFunctionCallArgs();
        Function fn = Functions.getFunction(ident.val());
        if (fn == null) {
            fail(range(ident), String.format("unknown function with name %s", GoStrings.quote(ident.val())));
        }
        if (fn.experimental() && !options.enableExperimentalFunctions()) {
            fail(range(ident), String.format("function %s is not enabled", GoStrings.quote(ident.val())));
        }
        return Call.of(fn, args, new PositionRange(ident.pos(), lastClosing));
    }

    /** function_call_body / function_call_args 规则（聚合与函数共用）。 */
    private List<Expr> parseFunctionCallArgs() {
        advance(); // 消费 LEFT_PAREN（调用点已保证）
        List<Expr> args = new ArrayList<>();
        if (item.typ() == ItemType.RIGHT_PAREN) {
            advance();
            return args;
        }
        boolean afterComma = false;
        while (true) {
            if (item.typ() == ItemType.COMMA) {
                if (afterComma) {
                    // Go 规则 function_call_args COMMA：逗号后又非表达式
                    fail(range(item), "trailing commas not allowed in function call args");
                }
                unexpected("", "");
            }
            args.add(parseBinary(PREC_LOR));
            afterComma = false;
            if (item.typ() == ItemType.COMMA) {
                Item comma = item;
                advance();
                afterComma = true;
                if (item.typ() == ItemType.RIGHT_PAREN) {
                    fail(range(comma), "trailing commas not allowed in function call args");
                }
                continue;
            }
            if (item.typ() == ItemType.RIGHT_PAREN) {
                advance();
                return args;
            }
            unexpected("", "");
        }
    }

    // ====================================================================
    // 分组标签（grouping_labels / maybe_grouping_labels / grouping_label）
    // ====================================================================

    private List<String> parseMaybeGroupingLabels() {
        if (item.typ() == ItemType.LEFT_PAREN) {
            return parseGroupingLabels();
        }
        return null;
    }

    private List<String> parseGroupingLabels() {
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected("grouping opts", "\"(\"");
        }
        advance();
        List<String> labels = new ArrayList<>();
        if (item.typ() == ItemType.RIGHT_PAREN) {
            advance();
            return labels;
        }
        while (true) {
            if (isMaybeLabel(item.typ())) {
                Item l = item;
                if (!Labels.isValidLabelName(l.val())) {
                    fail(range(l), String.format("invalid label name for grouping: %s", GoStrings.quote(l.val())));
                }
                advance();
                labels.add(l.val());
            } else if (item.typ() == ItemType.STRING) {
                Item s = item;
                String unquoted = unquoteString(s.val());
                if (!Labels.isValidLabelName(unquoted)) {
                    fail(range(s), String.format("invalid label name for grouping: %s", GoStrings.quote(unquoted)));
                }
                advance();
                labels.add(unquoted);
            } else {
                unexpected("grouping opts", "label");
            }
            if (item.typ() == ItemType.COMMA) {
                advance();
                if (item.typ() == ItemType.RIGHT_PAREN) {
                    advance();
                    return labels;
                }
                continue;
            }
            if (item.typ() == ItemType.RIGHT_PAREN) {
                advance();
                return labels;
            }
            unexpected("grouping opts", "\",\" or \")\"");
        }
    }

    // ====================================================================
    // 向量选择器（vector_selector / label_matchers / label_matcher）
    // ====================================================================

    private VectorSelector parseVectorSelector() {
        if (isMetricIdentifier(item.typ())) {
            Item n = item;
            advance();
            return vectorSelectorNamed(n);
        }
        if (item.typ() == ItemType.LEFT_BRACE) {
            MatcherList ml = parseLabelMatchers();
            return VectorSelector.of("", 0L, null, null, StartOrEnd.NONE,
                    ml.matchers(), false, false, ml.range());
        }
        unexpected("", "");
        return null;
    }

    /** 名字已消费的 vector_selector：可选 {@code {匹配器}}，然后 assemble 追加 {@code __name__} 匹配器。 */
    private VectorSelector vectorSelectorNamed(Item nameItem) {
        String name = nameItem.val();
        if (item.typ() == ItemType.LEFT_BRACE) {
            MatcherList ml = parseLabelMatchers();
            List<LabelMatcher> ms = new ArrayList<>(ml.matchers());
            if (!name.isEmpty()) {
                ms.add(new LabelMatcher(Labels.METRIC_NAME, MatchType.EQUAL, name));
            }
            return VectorSelector.of(name, 0L, null, null, StartOrEnd.NONE,
                    ms, false, false, new PositionRange(nameItem.pos(), ml.range().end()));
        }
        List<LabelMatcher> ms = new ArrayList<>();
        if (!name.isEmpty()) {
            ms.add(new LabelMatcher(Labels.METRIC_NAME, MatchType.EQUAL, name));
        }
        return VectorSelector.of(name, 0L, null, null, StartOrEnd.NONE,
                ms, false, false, range(nameItem));
    }

    private record MatcherList(List<LabelMatcher> matchers, PositionRange range) {
    }

    private MatcherList parseLabelMatchers() {
        Item lbrace = item;
        advance();
        List<LabelMatcher> ms = new ArrayList<>();
        Item rbrace;
        if (item.typ() == ItemType.RIGHT_BRACE) {
            rbrace = item;
            advance();
            return new MatcherList(ms, itemMerge(lbrace, rbrace));
        }
        while (true) {
            ms.add(parseLabelMatcher());
            if (item.typ() == ItemType.COMMA) {
                advance();
                if (item.typ() == ItemType.RIGHT_BRACE) {
                    rbrace = item;
                    advance();
                    break;
                }
                continue;
            }
            if (item.typ() == ItemType.RIGHT_BRACE) {
                rbrace = item;
                advance();
                break;
            }
            unexpected("label matching", "\",\" or \"}\"");
        }
        return new MatcherList(ms, itemMerge(lbrace, rbrace));
    }

    private LabelMatcher parseLabelMatcher() {
        if (item.typ() == ItemType.STRING) {
            // string_identifier：反引号内的字符串可作指标名/标签名
            Item s = item;
            Item m = new Item(ItemType.METRIC_IDENTIFIER, s.pos(), unquoteString(s.val()));
            advance();
            if (isMatchOp(item.typ())) {
                Item op = item;
                advance();
                if (item.typ() != ItemType.STRING) {
                    unexpected("label matching", "string");
                }
                Item v = item;
                advance();
                return newLabelMatcher(m, op, v);
            }
            return newMetricNameMatcher(m);
        }
        if (item.typ() == ItemType.IDENTIFIER) {
            Item label = item;
            advance();
            if (!isMatchOp(item.typ())) {
                unexpected("label matching", "label matching operator");
            }
            Item op = item;
            advance();
            if (item.typ() != ItemType.STRING) {
                unexpected("label matching", "string");
            }
            Item v = item;
            advance();
            return newLabelMatcher(label, op, v);
        }
        unexpected("label matching", "identifier or \"}\"");
        return null;
    }

    /** Go {@code newLabelMatcher}：匹配类型映射 + 正则编译校验。 */
    private LabelMatcher newLabelMatcher(Item label, Item operator, Item value) {
        MatchType matchType = switch (operator.typ()) {
            case EQL -> MatchType.EQUAL;
            case NEQ -> MatchType.NOT_EQUAL;
            case EQL_REGEX -> MatchType.REGEXP;
            case NEQ_REGEX -> MatchType.NOT_REGEXP;
            default -> throw new IllegalStateException("invalid operator");
        };
        String val = unquoteString(value.val());
        if (matchType == MatchType.REGEXP || matchType == MatchType.NOT_REGEXP) {
            try {
                Pattern.compile("^(?:" + val + ")$");
            } catch (PatternSyntaxException e) {
                // Go 透传 RE2 错误（“error parsing regexp: …”）；Java 正则引擎的
                // 错误文本不同，为已知分歧（见 PORTING.md）。
                fail(itemMerge(label, value), "error parsing regexp: " + e.getMessage());
            }
        }
        return new LabelMatcher(label.val(), matchType, val);
    }

    /** Go {@code newMetricNameMatcher}：{@code {"foo"}} 形式的指标名匹配器。 */
    private LabelMatcher newMetricNameMatcher(Item value) {
        return new LabelMatcher(Labels.METRIC_NAME, MatchType.EQUAL, value.val());
    }

    /** Go {@code unquoteString}：失败即报 “error unquoting string …: invalid syntax”。 */
    private String unquoteString(String s) {
        try {
            return GoStrings.unquote(s);
        } catch (RuntimeException e) {
            fail(range(item), String.format("error unquoting string %s: invalid syntax", GoStrings.quote(s)));
            return null;
        }
    }

    // ====================================================================
    // 区间/子查询后缀（matrix_selector / subquery_expr）
    // ====================================================================

    private Expr parseBracket(Expr e) {
        Item lb = item;
        advance(); // 消费 LEFT_BRACKET
        Expr rangePart = parsePositiveDurationExpr("subquery or range selector");
        if (item.typ() == ItemType.COLON) {
            advance();
            Expr stepPart = null;
            if (canStartDuration(item.typ())) {
                stepPart = parsePositiveDurationExpr("subquery or range selector");
            } else if (item.typ() != ItemType.RIGHT_BRACKET) {
                unexpected("subquery selector", "number, duration, step(), range(), or \"]\"");
            }
            if (item.typ() != ItemType.RIGHT_BRACKET) {
                unexpected("subquery selector", "\"]\"");
            }
            Item rb = item;
            advance();
            return SubqueryExpr.of(e,
                    literalNanos(rangePart), asDurationExpr(rangePart),
                    0L, null, null, StartOrEnd.NONE,
                    literalNanos(stepPart), asDurationExpr(stepPart),
                    rb.pos() + 1);
        }
        if (item.typ() != ItemType.RIGHT_BRACKET) {
            unexpected("subquery or range", "\":\" or \"]\"");
        }
        Item rb = item;
        advance();
        // Go：mergeRanges(左方括号, 右方括号) → [lb.pos, rb.pos+1)，覆盖整个方括号对。
        PositionRange errRange = new PositionRange(lb.pos(), rb.pos() + 1);
        if (!(e instanceof VectorSelector vs)) {
            fail(errRange, "ranges only allowed for vector selectors");
        } else if (vs.originalOffset() != 0 || vs.originalOffsetExpr() != null) {
            fail(errRange, "no offset modifiers allowed before range");
        } else if (vs.timestamp() != null || vs.startOrEnd() != StartOrEnd.NONE) {
            fail(errRange, "no @ modifiers allowed before range");
        }
        return MatrixSelector.of(e, literalNanos(rangePart), asDurationExpr(rangePart), lastClosing);
    }

    /** 正时长（positive_duration_expr）：数字字面量必须 &gt; 0。 */
    private Expr parsePositiveDurationExpr(String ctx) {
        Expr e = parseDurationExpr(ctx);
        if (e instanceof NumberLiteral nl && nl.val() <= 0) {
            fail(nl.positionRange(), "duration must be greater than 0");
        }
        return e;
    }

    /** Go {@code time.Duration(math.Round(nl.Val * float64(time.Second)))}；非数字字面量为 0。 */
    private static long literalNanos(Expr e) {
        return e instanceof NumberLiteral nl ? Math.round(nl.val() * 1e9) : 0L;
    }

    private static DurationExpr asDurationExpr(Expr e) {
        return e instanceof DurationExpr de ? de : null;
    }

    // ====================================================================
    // offset / @ / anchored / smoothed 修饰符（不可变重建版）
    // ====================================================================

    /**
     * Go {@code addOffset} / {@code addOffsetExpr} 合并实现：
     * 字面量 offset（offsetNs 非 0、expr 为 null）或表达式 offset（反之）。
     */
    private Expr applyOffset(Expr e, long offsetNs, DurationExpr offsetExpr) {
        if (e instanceof VectorSelector vs) {
            if (vs.originalOffset() != 0 || vs.originalOffsetExpr() != null) {
                fail(e.positionRange(), "offset may not be set multiple times");
            }
            return VectorSelector.of(vs.name(), offsetNs, offsetExpr, vs.timestamp(), vs.startOrEnd(),
                    vs.labelMatchers(), vs.anchored(), vs.smoothed(),
                    withEnd(vs.positionRange(), lastClosing));
        }
        if (e instanceof MatrixSelector ms) {
            VectorSelector vs = ms.vectorSelector() instanceof VectorSelector v ? v : null;
            if (vs == null) {
                fail(e.positionRange(), "ranges only allowed for vector selectors");
            }
            if (vs.originalOffset() != 0 || vs.originalOffsetExpr() != null) {
                fail(e.positionRange(), "offset may not be set multiple times");
            }
            VectorSelector nvs = VectorSelector.of(vs.name(), offsetNs, offsetExpr, vs.timestamp(),
                    vs.startOrEnd(), vs.labelMatchers(), vs.anchored(), vs.smoothed(), vs.positionRange());
            return MatrixSelector.of(nvs, ms.range(), ms.rangeExpr(), lastClosing);
        }
        if (e instanceof SubqueryExpr sq) {
            if (sq.originalOffset() != 0 || sq.originalOffsetExpr() != null) {
                fail(e.positionRange(), "offset may not be set multiple times");
            }
            return SubqueryExpr.of(sq.expr(), sq.range(), sq.rangeExpr(), offsetNs, offsetExpr,
                    sq.timestamp(), sq.startOrEnd(), sq.step(), sq.stepExpr(), lastClosing);
        }
        fail(e.positionRange(), "offset modifier must be preceded by an instant vector selector or range vector selector or a subquery");
        return null;
    }

    /** step_invariant_expr 的 AT 部分（AT 已消费）。 */
    private Expr parseAtModifier(Expr e) {
        if (item.typ() == ItemType.START || item.typ() == ItemType.END) {
            Item kw = item;
            advance();
            if (item.typ() != ItemType.LEFT_PAREN) {
                unexpected("@", "timestamp");
            }
            advance();
            if (item.typ() != ItemType.RIGHT_PAREN) {
                unexpected("@", "timestamp");
            }
            advance();
            return applyAt(e, null, kw.typ() == ItemType.START ? StartOrEnd.START : StartOrEnd.END);
        }
        double sign = 1;
        if (item.typ() == ItemType.ADD || item.typ() == ItemType.SUB) {
            sign = item.typ() == ItemType.SUB ? -1 : 1;
            advance();
        }
        if (item.typ() != ItemType.NUMBER && item.typ() != ItemType.DURATION) {
            unexpected("@", "timestamp");
        }
        Item num = item;
        advance();
        double v = sign * numberOrDurationSeconds(num);
        return setTimestamp(e, v);
    }

    /** Go {@code setTimestamp}：界检查 + 应用（Go timestamp.FromFloatSeconds 为毫秒精度）。 */
    private Expr setTimestamp(Expr e, double ts) {
        if (Double.isInfinite(ts) || Double.isNaN(ts)
                || ts >= (double) Long.MAX_VALUE || ts <= (double) Long.MIN_VALUE) {
            fail(e.positionRange(), String.format("timestamp out of bounds for @ modifier: %s", formatF6(ts)));
        }
        return applyAt(e, Math.round(ts * 1000), null);
    }

    /** Go {@code getAtModifierVars} + 写回：定位目标选择器、查重、重建（preproc 为 null 时归一化为 NONE）。 */
    private Expr applyAt(Expr e, Long ts, StartOrEnd preproc) {
        StartOrEnd soe = preproc == null ? StartOrEnd.NONE : preproc;
        if (e instanceof VectorSelector vs) {
            if (vs.timestamp() != null || vs.startOrEnd() != StartOrEnd.NONE) {
                fail(e.positionRange(), "@ <timestamp> may not be set multiple times");
            }
            return VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(),
                    ts, soe, vs.labelMatchers(), vs.anchored(), vs.smoothed(),
                    withEnd(vs.positionRange(), lastClosing));
        }
        if (e instanceof MatrixSelector ms) {
            VectorSelector vs = ms.vectorSelector() instanceof VectorSelector v ? v : null;
            if (vs == null) {
                fail(e.positionRange(), "ranges only allowed for vector selectors");
            }
            if (vs.timestamp() != null || vs.startOrEnd() != StartOrEnd.NONE) {
                fail(e.positionRange(), "@ <timestamp> may not be set multiple times");
            }
            VectorSelector nvs = VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(),
                    ts, soe, vs.labelMatchers(), vs.anchored(), vs.smoothed(), vs.positionRange());
            return MatrixSelector.of(nvs, ms.range(), ms.rangeExpr(), lastClosing);
        }
        if (e instanceof SubqueryExpr sq) {
            if (sq.timestamp() != null || sq.startOrEnd() != StartOrEnd.NONE) {
                fail(e.positionRange(), "@ <timestamp> may not be set multiple times");
            }
            return SubqueryExpr.of(sq.expr(), sq.range(), sq.rangeExpr(), sq.originalOffset(),
                    sq.originalOffsetExpr(), ts, soe, sq.step(), sq.stepExpr(), lastClosing);
        }
        fail(e.positionRange(), "@ modifier must be preceded by an instant vector selector or range vector selector or a subquery");
        return null;
    }

    /** Go {@code setAnchored}（不更新收尾位置）。 */
    private Expr setAnchored(Expr e) {
        if (!options.enableExtendedRangeSelectors()) {
            fail(e.positionRange(), "anchored modifier is experimental and not enabled");
        }
        if (e instanceof VectorSelector vs) {
            if (vs.smoothed()) {
                fail(e.positionRange(), "anchored and smoothed modifiers cannot be used together");
            }
            return VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(), vs.timestamp(),
                    vs.startOrEnd(), vs.labelMatchers(), true, vs.smoothed(), vs.positionRange());
        }
        if (e instanceof MatrixSelector ms) {
            if (!(ms.vectorSelector() instanceof VectorSelector vs)) {
                return e; // Go：非向量选择器内层时静默返回
            }
            if (vs.smoothed()) {
                fail(e.positionRange(), "anchored and smoothed modifiers cannot be used together");
            }
            VectorSelector nvs = VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(),
                    vs.timestamp(), vs.startOrEnd(), vs.labelMatchers(), true, vs.smoothed(), vs.positionRange());
            return MatrixSelector.of(nvs, ms.range(), ms.rangeExpr(), ms.endPos());
        }
        if (e instanceof SubqueryExpr) {
            fail(e.positionRange(), "anchored modifier is not supported for subqueries");
        }
        fail(e.positionRange(), "anchored modifier not implemented");
        return null;
    }

    /** Go {@code setSmoothed}（不更新收尾位置）。 */
    private Expr setSmoothed(Expr e) {
        if (!options.enableExtendedRangeSelectors()) {
            fail(e.positionRange(), "smoothed modifier is experimental and not enabled");
        }
        if (e instanceof VectorSelector vs) {
            if (vs.anchored()) {
                fail(e.positionRange(), "anchored and smoothed modifiers cannot be used together");
            }
            return VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(), vs.timestamp(),
                    vs.startOrEnd(), vs.labelMatchers(), vs.anchored(), true, vs.positionRange());
        }
        if (e instanceof MatrixSelector ms) {
            if (!(ms.vectorSelector() instanceof VectorSelector vs)) {
                return e;
            }
            if (vs.anchored()) {
                fail(e.positionRange(), "anchored and smoothed modifiers cannot be used together");
            }
            VectorSelector nvs = VectorSelector.of(vs.name(), vs.originalOffset(), vs.originalOffsetExpr(),
                    vs.timestamp(), vs.startOrEnd(), vs.labelMatchers(), vs.anchored(), true, vs.positionRange());
            return MatrixSelector.of(nvs, ms.range(), ms.rangeExpr(), ms.endPos());
        }
        if (e instanceof SubqueryExpr) {
            fail(e.positionRange(), "smoothed modifier is not supported for subqueries");
        }
        fail(e.positionRange(), "smoothed modifier not implemented");
        return null;
    }

    private static PositionRange withEnd(PositionRange r, int end) {
        return new PositionRange(r.start(), end);
    }

    // ====================================================================
    // 时长子文法（offset_duration_expr / duration_expr / paren_duration_expr）
    // ====================================================================

    /**
     * offset_duration_expr 规则（.y 1186-1281 行），按备选次序逐一对齐。
     * 失败上下文为 “offset”。
     */
    private Expr parseOffsetDuration() {
        if (item.typ() == ItemType.NUMBER || item.typ() == ItemType.DURATION) {
            Item it = item;
            NumberLiteral nl = numberDurationLiteral();
            if (durationLiteralOutOfRange(nl.val())) {
                fail(range(it), "duration out of range");
            }
            return nl;
        }
        if (item.typ() == ItemType.ADD || item.typ() == ItemType.SUB) {
            Item op = item;
            advance();
            if (item.typ() == ItemType.NUMBER || item.typ() == ItemType.DURATION) {
                Item it = item;
                NumberLiteral nl = numberDurationLiteral();
                double v = op.typ() == ItemType.SUB ? -nl.val() : nl.val();
                if (durationLiteralOutOfRange(v)) {
                    fail(range(op), "duration out of range");
                }
                return NumberLiteral.of(v, nl.duration(), new PositionRange(op.pos(), range(it).end()));
            }
            if (item.typ() == ItemType.STEP || item.typ() == ItemType.RANGE) {
                return unaryDurationCall(op);
            }
            if (item.typ() == ItemType.MAX_OF || item.typ() == ItemType.MIN_OF) {
                return unaryMaxOfMinOf(op);
            }
            if (item.typ() == ItemType.LEFT_PAREN) {
                advance();
                Expr inner = parseDurationExpr("offset");
                if (item.typ() != ItemType.RIGHT_PAREN) {
                    unexpected("offset", "number, duration, step(), or range()");
                }
                advance();
                return applyUnaryOpToDurationExpr(op, inner, true);
            }
            unexpected("offset", "number, duration, step(), or range()");
        }
        if (item.typ() == ItemType.STEP || item.typ() == ItemType.RANGE) {
            return durationCall();
        }
        if (item.typ() == ItemType.MAX_OF || item.typ() == ItemType.MIN_OF) {
            return maxOfMinOf();
        }
        return parseDurationExpr("offset");
    }

    /** {@code STEP()} / {@code RANGE()}（无符号形式）。 */
    private DurationExpr durationCall() {
        Item fn = item;
        Item rp = durationCallTail();
        DurationExpr de = DurationExpr.of(durationPreprocOp(fn), null, null, false,
                fn.pos(), range(rp).end());
        experimentalDurationExpr(de);
        return de;
    }

    /** {@code unary_op STEP()} / {@code unary_op RANGE()}。 */
    private DurationExpr unaryDurationCall(Item op) {
        Item fn = item;
        Item rp = durationCallTail();
        DurationExpr inner = DurationExpr.of(durationPreprocOp(fn), null, null, false,
                fn.pos(), range(rp).end());
        DurationExpr de = DurationExpr.of(op.typ() == ItemType.SUB ? DurationOp.SUB : DurationOp.ADD,
                null, inner, false, op.pos(), 0);
        experimentalDurationExpr(de);
        return de;
    }

    /** 消费 {@code STEP/RANGE ( )}，返回右括号词法单元。 */
    private Item durationCallTail() {
        advance();
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected("offset", "number, duration, step(), or range()");
        }
        advance();
        if (item.typ() != ItemType.RIGHT_PAREN) {
            unexpected("offset", "number, duration, step(), or range()");
        }
        Item rp = item;
        advance();
        return rp;
    }

    /** {@code max_of(a, b)} / {@code min_of(a, b)}（无符号形式）。 */
    private DurationExpr maxOfMinOf() {
        return maxOfMinOfDuration("offset");
    }

    /**
     * {@code unary_op max_of(a, b)}：注意 Go 语义动作中外层与内层的 EndPos
     * 都取 <b>第二个时长表达式</b> 的末尾（{$}6），而非右括号--按原样移植。
     */
    private DurationExpr unaryMaxOfMinOf(Item op) {
        Item fn = item;
        advance();
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected("offset", "number, duration, step(), or range()");
        }
        advance();
        Expr l = parseDurationExpr("offset");
        if (item.typ() != ItemType.COMMA) {
            unexpected("offset", "number, duration, step(), or range()");
        }
        advance();
        Expr r = parseDurationExpr("offset");
        int secondEnd = r.positionRange().end();
        if (item.typ() != ItemType.RIGHT_PAREN) {
            unexpected("offset", "number, duration, step(), or range()");
        }
        advance();
        DurationExpr inner = DurationExpr.of(maxOfMinOfOp(fn), l, r, false, fn.pos(), secondEnd);
        DurationExpr de = DurationExpr.of(op.typ() == ItemType.SUB ? DurationOp.SUB : DurationOp.ADD,
                null, inner, false, op.pos(), secondEnd);
        experimentalDurationExpr(de);
        return de;
    }

    /**
     * duration_expr 入口（含一元）。
     *
     * @param ctx 失败时 unexpected 的上下文（“offset” 或 “subquery or range selector”）
     */
    private Expr parseDurationExpr(String ctx) {
        return parseDurationBinary(ctx, PREC_LOR);
    }

    private Expr parseDurationBinary(String ctx, int minPrec) {
        Expr lhs;
        if (item.typ() == ItemType.ADD || item.typ() == ItemType.SUB) {
            // Go 文法 unary %prec MUL：操作数按 PREC_POW 解析（-2*3 → (-2)*3），
            // 归约后回到<b>本层</b> minPrec 的二元循环继续（-10s+15s → (-10s)+15s）。
            Item op = item;
            advance();
            Expr operand = parseDurationBinary(ctx, PREC_POW);
            lhs = applyUnaryOpToDurationExpr(op, operand, false);
        } else {
            lhs = parseDurationUnary(ctx);
        }
        while (true) {
            DurationOp op = durationOpOf(item.typ());
            if (op == null) {
                break;
            }
            int prec = durationPrec(item.typ());
            if (prec < minPrec) {
                break;
            }
            Item opItem = item;
            advance();
            experimentalDurationExpr(lhs);
            Expr rhs = prec == PREC_POW ? parseDurationBinary(ctx, prec) : parseDurationBinary(ctx, prec + 1);
            if (op == DurationOp.DIV && rhs instanceof NumberLiteral nl && nl.val() == 0) {
                fail(range(opItem), "division by zero");
            }
            if (op == DurationOp.MOD && rhs instanceof NumberLiteral nl2 && nl2.val() == 0) {
                fail(range(opItem), "modulo by zero");
            }
            lhs = DurationExpr.of(op, lhs, rhs, false, 0, 0);
        }
        return lhs;
    }

    private Expr parseDurationUnary(String ctx) {
        String expected = "number, duration, step(), or range()";
        switch (item.typ()) {
            case NUMBER, DURATION: {
                Item it = item;
                NumberLiteral nl = numberDurationLiteral();
                if (durationLiteralOutOfRange(nl.val())) {
                    fail(range(it), "duration out of range");
                }
                return nl;
            }
            case STEP, RANGE: {
                Item fn = item;
                advance();
                if (item.typ() != ItemType.LEFT_PAREN) {
                    unexpected(ctx, expected);
                }
                advance();
                if (item.typ() != ItemType.RIGHT_PAREN) {
                    unexpected(ctx, expected);
                }
                Item rp = item;
                advance();
                DurationExpr de = DurationExpr.of(durationPreprocOp(fn), null, null, false,
                        fn.pos(), range(rp).end());
                experimentalDurationExpr(de);
                return de;
            }
            case MAX_OF, MIN_OF:
                return maxOfMinOfDuration(ctx);
            case LEFT_PAREN: {
                Item lp = item;
                advance();
                Expr inner = parseDurationExpr(ctx);
                if (item.typ() != ItemType.RIGHT_PAREN) {
                    unexpected(ctx, expected);
                }
                Item rp = item;
                advance();
                experimentalDurationExpr(inner);
                return wrapParenDurationExpr(inner, lp.pos(), range(rp).end());
            }
            default:
                unexpected(ctx, expected);
                return null;
        }
    }

    /** 括号/offset 上下文中的 {@code max_of/min_of}（错误上下文由调用方传入）。 */
    private DurationExpr maxOfMinOfDuration(String ctx) {
        String expected = "number, duration, step(), or range()";
        Item fn = item;
        advance();
        if (item.typ() != ItemType.LEFT_PAREN) {
            unexpected(ctx, expected);
        }
        advance();
        Expr l = parseDurationExpr(ctx);
        if (item.typ() != ItemType.COMMA) {
            unexpected(ctx, expected);
        }
        advance();
        Expr r = parseDurationExpr(ctx);
        if (item.typ() != ItemType.RIGHT_PAREN) {
            unexpected(ctx, expected);
        }
        Item rp = item;
        advance();
        DurationExpr de = DurationExpr.of(maxOfMinOfOp(fn), l, r, false, fn.pos(), range(rp).end());
        experimentalDurationExpr(de);
        return de;
    }

    /** Go {@code wrapParenDurationExpr}：标记括号包裹以支持 String() 往返。 */
    private static Expr wrapParenDurationExpr(Expr expr, int start, int end) {
        if (expr instanceof DurationExpr de) {
            return DurationExpr.of(de.op(), de.lhs(), de.rhs(), true, de.startPos(), de.endPos());
        }
        return DurationExpr.of(DurationOp.ADD, null, expr, true, start, end);
    }

    /** Go {@code applyUnaryOpToDurationExpr}。 */
    private Expr applyUnaryOpToDurationExpr(Item op, Expr expr, boolean wrapped) {
        if (wrapped) {
            expr = wrapParenDurationExpr(expr, expr.positionRange().start(), expr.positionRange().end());
        }
        if (expr instanceof DurationExpr de) {
            if (op.typ() == ItemType.SUB) {
                return DurationExpr.of(DurationOp.SUB, null, de, false, op.pos(), 0);
            }
            return de;
        }
        if (expr instanceof NumberLiteral nl) {
            double v = op.typ() == ItemType.SUB ? -nl.val() : nl.val();
            if (durationLiteralOutOfRange(v)) {
                fail(range(op), "duration out of range");
            }
            return NumberLiteral.of(v, nl.duration(), new PositionRange(op.pos(), nl.positionRange().end()));
        }
        fail(range(op), "expected number literal or duration expression");
        return null;
    }

    /** Go {@code experimentalDurationExpr}：仅累积、不中止。 */
    private void experimentalDurationExpr(Expr e) {
        if (!options.experimentalDurationExpr()) {
            addParseErrf(e.positionRange(), "experimental duration expression is not enabled");
        }
    }

    /** Go {@code durationLiteralOutOfRange}。 */
    private static boolean durationLiteralOutOfRange(double val) {
        return val > MAX_DURATION_LITERAL_SECONDS || val < -MAX_DURATION_LITERAL_SECONDS;
    }

    // ====================================================================
    // 字面量与数值解析（number_duration_literal / number / parseDuration）
    // ====================================================================

    /** number_duration_literal 规则：NUMBER 走 Go strconv，DURATION 走 model.ParseDuration（秒值 + duration 标记）。 */
    private NumberLiteral numberDurationLiteral() {
        Item it = item;
        if (it.typ() == ItemType.DURATION) {
            double seconds;
            try {
                seconds = DurationFormat.parse(it.val()) / 1e9;
            } catch (IllegalArgumentException e) {
                fail(range(it), e.getMessage());
                return null;
            }
            advance();
            return NumberLiteral.of(seconds, true, range(it));
        }
        double v = number(it);
        advance();
        return NumberLiteral.of(v, false, range(it));
    }

    /** Go {@code parser.number}：先 ParseInt(0)，再 ParseFloat；错误文本按 Go strconv 形式构造。 */
    private double number(Item it) {
        try {
            return (double) parseGoInt64(it.val());
        } catch (NumberFormatException e1) {
            try {
                double f = GoFloat.parseFloat(it.val());
                if (Double.isInfinite(f) && !isInfLiteral(it.val())) {
                    // Go ParseFloat 接受 Inf/Infinity/NaN 字面量；数值上溢（如 1e999）
                    // 才报 ErrRange；下溢分歧见 PORTING.md。
                    throw new NumberFormatException(String.format(
                            "strconv.ParseFloat: parsing %s: value out of range", GoStrings.quote(it.val())));
                }
                return f;
            } catch (NumberFormatException e2) {
                fail(range(it), String.format("error parsing number: %s", e2.getMessage()));
                return Double.NaN;
            }
        }
    }

    /** Go ParseFloat 接受的字面量：{@code Inf/Infinity/NaN}（忽略符号与大小写）。 */
    private static boolean isInfLiteral(String s) {
        String t = s.startsWith("+") || s.startsWith("-") ? s.substring(1) : s;
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "inf", "infinity", "nan" -> true;
            default -> false;
        };
    }

    /** signed_or_unsigned_number 的数值：NUMBER 或 DURATION（秒）。 */    private double numberOrDurationSeconds(Item it) {
        if (it.typ() == ItemType.DURATION) {
            try {
                return DurationFormat.parse(it.val()) / 1e9;
            } catch (IllegalArgumentException e) {
                fail(range(it), e.getMessage());
                return Double.NaN;
            }
        }
        return number(it);
    }

    /**
     * Go {@code strconv.ParseInt(s, 0, 64)}：0x/0X 十六进制、0b/0B 二进制、
     * 0o/0O 或前导 0 八进制、下划线分隔（仅限带前缀字面量）。
     */
    private static long parseGoInt64(String s) {
        String t = s;
        boolean neg = false;
        if (t.startsWith("+")) {
            t = t.substring(1);
        } else if (t.startsWith("-")) {
            neg = true;
            t = t.substring(1);
        }
        int radix = 10;
        if (t.startsWith("0x") || t.startsWith("0X")) {
            radix = 16;
            t = t.substring(2);
        } else if (t.startsWith("0b") || t.startsWith("0B")) {
            radix = 2;
            t = t.substring(2);
        } else if (t.startsWith("0o") || t.startsWith("0O")) {
            radix = 8;
            t = t.substring(2);
        } else if (t.length() > 1 && t.startsWith("0")) {
            radix = 8;
            t = t.substring(1);
        }
        t = t.replace("_", "");
        if (t.isEmpty()) {
            throw new NumberFormatException(s);
        }
        return Long.parseLong((neg ? "-" : "") + t, radix);
    }

    /** Go {@code fmt %f}（6 位定点小数；Inf/NaN 特殊形式）。 */
    private static String formatF6(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ====================================================================
    // checkAST（parse.go 721-932 行；返回可能重建过的节点）
    // ====================================================================

    /**
     * 递归类型/语义检查。Go 原地改写 VectorMatching（集合算子 Card 修复、
     * 非向量对置 nil）；本版返回重建后的节点供父层回接。
     */
    @SuppressWarnings("all")
    private Expr checkAST(Expr node) {
        if (node instanceof AggregateExpr a) {
            Expr expr2 = expectType(a.expr(), com.promql.value.ValueType.VECTOR, "aggregation expression");
            Expr param2 = a.param();
            AggregateOp op = a.op();
            if (op == AggregateOp.TOPK || op == AggregateOp.BOTTOMK || op == AggregateOp.QUANTILE
                    || op == AggregateOp.LIMITK || op == AggregateOp.LIMIT_RATIO) {
                param2 = expectType(a.param(), com.promql.value.ValueType.SCALAR, "aggregation parameter");
            }
            if (op == AggregateOp.COUNT_VALUES) {
                param2 = expectType(a.param(), com.promql.value.ValueType.STRING, "aggregation parameter");
            }
            if (expr2 != a.expr() || param2 != a.param()) {
                node = AggregateExpr.of(a.op(), expr2, param2, a.grouping(), a.without(), a.posRange());
            }
        } else if (node instanceof BinaryExpr b) {
            node = checkBinary(b);
        } else if (node instanceof Call c) {
            node = checkCall(c);
        } else if (node instanceof ParenExpr p) {
            Expr e2 = checkAST(p.expr());
            if (e2 != p.expr()) {
                node = ParenExpr.of(e2, p.posRange());
            }
        } else if (node instanceof UnaryExpr u) {
            if (u.op() != UnaryOp.PLUS && u.op() != UnaryOp.MINUS) {
                addParseErrf(node.positionRange(), "only + and - operators allowed for unary expressions");
            }
            Expr e2 = checkAST(u.expr());
            if (e2.type() != com.promql.value.ValueType.SCALAR && e2.type() != com.promql.value.ValueType.VECTOR) {
                addParseErrf(node.positionRange(), String.format(
                        "unary expression only allowed on expressions of type scalar or instant vector, got %s",
                        GoStrings.quote(e2.type().documentedType())));
            }
            if (e2 != u.expr()) {
                node = UnaryExpr.of(u.op(), e2, u.startPos());
            }
        } else if (node instanceof SubqueryExpr s) {
            Expr e2 = checkAST(s.expr());
            if (e2.type() != com.promql.value.ValueType.VECTOR) {
                addParseErrf(node.positionRange(), String.format(
                        "subquery is only allowed on instant vector, got %s instead", e2.type().symbol()));
            }
            if (e2 != s.expr()) {
                node = SubqueryExpr.of(e2, s.range(), s.rangeExpr(), s.originalOffset(),
                        s.originalOffsetExpr(), s.timestamp(), s.startOrEnd(), s.step(), s.stepExpr(),
                        s.endPos());
            }
        } else if (node instanceof MatrixSelector m) {
            Expr v2 = checkAST(m.vectorSelector());
            if (v2 != m.vectorSelector()) {
                node = MatrixSelector.of(v2, m.range(), m.rangeExpr(), m.endPos());
            }
        } else if (node instanceof VectorSelector v) {
            if (!v.name().isEmpty()) {
                List<LabelMatcher> ms = v.labelMatchers();
                for (int i = 0; i < ms.size() - 1; i++) {
                    LabelMatcher m = ms.get(i);
                    if (m != null && Labels.METRIC_NAME.equals(m.name())) {
                        addParseErrf(node.positionRange(), String.format(
                                "metric name must not be set twice: %s or %s",
                                GoStrings.quote(v.name()), GoStrings.quote(m.value())));
                    }
                }
                // 显式指标名本身即非空匹配器，跳过非空检查
            } else if (!infoBypass.contains(v)) {
                boolean notEmpty = false;
                for (LabelMatcher lm : v.labelMatchers()) {
                    if (lm != null && !matchesEmpty(lm)) {
                        notEmpty = true;
                        break;
                    }
                }
                if (!notEmpty) {
                    addParseErrf(node.positionRange(),
                            "vector selector must contain at least one non-empty matcher");
                }
            }
        } else if (node instanceof DurationExpr) {
            // Go checkAST 无 DurationExpr 分支（落 default “unknown node type”）；
            // 裸时长表达式在 expr 层不可达（仅出现于 []/offset 上下文），保持逐字移植。
            addParseErrf(node.positionRange(), "unknown node type: *parser.DurationExpr");
        }
        // NumberLiteral / StringLiteral：叶子，无需检查
        return node;
    }

    /** Go {@code expectType}（返回检查后的节点以便回接）。 */
    private Expr expectType(Expr node, com.promql.value.ValueType want, String context) {
        Expr n = checkAST(node);
        if (n.type() != want) {
            addParseErrf(node.positionRange(), String.format("expected type %s in %s, got %s",
                    want.documentedType(), context, n.type().documentedType()));
        }
        return n;
    }

    /** Go checkAST 的 *BinaryExpr 分支。 */
    private Expr checkBinary(BinaryExpr b) {
        Expr lhs2 = checkAST(b.lhs());
        Expr rhs2 = checkAST(b.rhs());
        com.promql.value.ValueType lt = lhs2.type();
        com.promql.value.ValueType rt = rhs2.type();
        VectorMatching vm = b.vectorMatching();

        if (b.returnBool() && !b.op().isComparisonOperator()) {
            addParseErrf(opRange(b), "bool modifier can only be used on comparison operators");
        }
        if (b.op().isComparisonOperator() && !b.returnBool()
                && lt == com.promql.value.ValueType.SCALAR
                && rt == com.promql.value.ValueType.SCALAR) {
            addParseErrf(opRange(b), "comparisons between scalars must use BOOL modifier");
        }

        boolean vmChanged = false;
        if (b.op().isSetOperator() && vm.card() == VectorMatchCardinality.ONE_TO_ONE) {
            vm = new VectorMatching(VectorMatchCardinality.MANY_TO_MANY,
                    vm.matchingLabels() == null ? List.of() : vm.matchingLabels(), vm.on(),
                    vm.include() == null ? List.of() : vm.include(), vm.fillLhs(), vm.fillRhs());
            vmChanged = true;
        }

        List<String> matching = vm.matchingLabels() == null ? List.of() : vm.matchingLabels();
        List<String> include = vm.include() == null ? List.of() : vm.include();
        if (vm.on()) {
            for (String l1 : matching) {
                for (String l2 : include) {
                    if (l1.equals(l2)) {
                        addParseErrf(opRange(b), String.format(
                                "label %s must not occur in ON and GROUP clause at once", GoStrings.quote(l1)));
                    }
                }
            }
        }

        // Go：!n.Op.IsOperator() -> “binary expression does not support operator %q”；
        // 解析器产出的 BinaryOp 恒为合法算子，该分支不可达，从略。

        if (lt != com.promql.value.ValueType.SCALAR && lt != com.promql.value.ValueType.VECTOR) {
            addParseErrf(b.lhs().positionRange(),
                    "binary expression must contain only scalar and instant vector types");
        }
        if (rt != com.promql.value.ValueType.SCALAR && rt != com.promql.value.ValueType.VECTOR) {
            addParseErrf(b.rhs().positionRange(),
                    "binary expression must contain only scalar and instant vector types");
        }

        if ((lt != com.promql.value.ValueType.VECTOR || rt != com.promql.value.ValueType.VECTOR) && vm != null) {
            if (!matching.isEmpty()) {
                addParseErrf(b.positionRange(), "vector matching only allowed between instant vectors");
            }
            if (vm.fillLhs() != null || vm.fillRhs() != null) {
                addParseErrf(b.positionRange(),
                        "filling in missing series only allowed between instant vectors");
            }
            vm = null;
            vmChanged = true;
        } else if (b.op().isSetOperator()) {
            if (vm.card() == VectorMatchCardinality.ONE_TO_MANY
                    || vm.card() == VectorMatchCardinality.MANY_TO_ONE) {
                addParseErrf(b.positionRange(), String.format("no grouping allowed for %s operation",
                        GoStrings.quote(b.op().symbol())));
            }
            if (vm.card() != VectorMatchCardinality.MANY_TO_MANY) {
                addParseErrf(b.positionRange(), "set operations must always be many-to-many");
            }
            if (vm.fillLhs() != null || vm.fillRhs() != null) {
                addParseErrf(b.positionRange(),
                        "filling in missing series not allowed for set operators");
            }
        }

        if ((lt == com.promql.value.ValueType.SCALAR || rt == com.promql.value.ValueType.SCALAR)
                && b.op().isSetOperator()) {
            addParseErrf(b.positionRange(), String.format("set operator %s not allowed in binary scalar expression",
                    GoStrings.quote(b.op().symbol())));
        }

        if (lhs2 != b.lhs() || rhs2 != b.rhs() || vmChanged) {
            return BinaryExpr.of(b.op(), lhs2, rhs2, vm, b.returnBool());
        }
        return b;
    }

    /** Go checkAST 的 *Call 分支。 */
    private Expr checkCall(Call c) {
        Function fn = c.function();
        int nargs = fn.argTypes().size();
        if (fn.variadic() == 0) {
            if (nargs != c.args().size()) {
                addParseErrf(c.positionRange(), String.format(
                        "expected %d argument(s) in call to %s, got %d", nargs,
                        GoStrings.quote(fn.name()), c.args().size()));
            }
        } else {
            int na = nargs - 1;
            if (na > c.args().size()) {
                addParseErrf(c.positionRange(), String.format(
                        "expected at least %d argument(s) in call to %s, got %d", na,
                        GoStrings.quote(fn.name()), c.args().size()));
            } else if (fn.variadic() > 0 && na + fn.variadic() < c.args().size()) {
                addParseErrf(c.positionRange(), String.format(
                        "expected at most %d argument(s) in call to %s, got %d", na + fn.variadic(),
                        GoStrings.quote(fn.name()), c.args().size()));
            }
        }

        if ("info".equals(fn.name()) && c.args().size() > 1) {
            Expr arg1 = c.args().get(1);
            if (arg1.type() != com.promql.value.ValueType.VECTOR) {
                addParseErrf(c.positionRange(), String.format("expected type %s in %s, got %s",
                        com.promql.value.ValueType.VECTOR.documentedType(),
                        String.format("call to function %s", GoStrings.quote(fn.name())),
                        arg1.type().documentedType()));
            }
            if (arg1 instanceof VectorSelector vs && !vs.name().isEmpty()) {
                addParseErrf(arg1.positionRange(),
                        "expected label selectors only, got vector selector instead");
            } else if (arg1 instanceof VectorSelector vs) {
                infoBypass.add(vs);
            } else {
                addParseErrf(arg1.positionRange(), "expected label selectors only");
            }
        }

        List<Expr> args2 = new ArrayList<>(c.args().size());
        boolean changed = false;
        boolean brokeEarly = false;
        for (int i = 0; i < c.args().size(); i++) {
            Expr arg = c.args().get(i);
            int idx = i;
            if (idx >= nargs) {
                if (fn.variadic() == 0) {
                    // Go 在此 break：多余实参不参与类型检查，原样保留
                    for (int j = i; j < c.args().size(); j++) {
                        args2.add(c.args().get(j));
                    }
                    brokeEarly = true;
                    break;
                }
                idx = nargs - 1;
            }
            Expr a2 = checkAST(arg);
            if (a2.type() != fn.argTypes().get(idx)) {
                addParseErrf(arg.positionRange(), String.format(
                        "expected type %s in call to function %s, got %s",
                        fn.argTypes().get(idx).documentedType(), GoStrings.quote(fn.name()),
                        a2.type().documentedType()));
            }
            if (a2 != arg) {
                changed = true;
            }
            args2.add(a2);
        }
        if (changed || brokeEarly) {
            return Call.of(fn, args2, c.posRange());
        }
        return c;
    }

    /**
     * 运算符区间（Go checkAST 内 opRange 闭包）：去掉两端空白。
     * Go 不做越界防护（合法解析时必在界内），此处加了界检查。
     */
    private PositionRange opRange(BinaryExpr n) {
        int start = n.lhs().positionRange().end();
        while (start < input.length() && isSpace(input.charAt(start))) {
            start++;
        }
        int end = n.rhs().positionRange().start() - 1;
        while (end >= 0 && isSpace(input.charAt(end))) {
            end--;
        }
        return new PositionRange(start, end);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '' || c == '\f'
                || Character.isSpaceChar(c) || Character.isWhitespace(c);
    }

    /** 匹配器是否匹配空串（Go {@code Matcher.Matches("")}）。 */
    private static boolean matchesEmpty(LabelMatcher m) {
        return switch (m.type()) {
            case EQUAL -> m.value().isEmpty();
            case NOT_EQUAL -> !m.value().isEmpty();
            // MatchNotRegexp.Matches("") = !re.MatchString("") → 对空串取反
            case REGEXP -> matchesEmptyRegexp(m.value());
            case NOT_REGEXP -> !matchesEmptyRegexp(m.value());
        };
    }

    private static boolean matchesEmptyRegexp(String v) {
        try {
            return Pattern.compile("^(?:" + v + ")$").matcher("").matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    // ====================================================================
    // 指标描述（metric / label_set）
    // ====================================================================

    private List<Label> parseLabelSet() {
        List<Label> out = new ArrayList<>();
        if (item.typ() != ItemType.LEFT_BRACE) {
            return out; // 空 label_set
        }
        advance();
        if (item.typ() != ItemType.RIGHT_BRACE) {
            while (true) {
                if (item.typ() == ItemType.IDENTIFIER) {
                    String name = item.val();
                    advance();
                    if (item.typ() != ItemType.EQL) {
                        unexpected("label set", "\"=\"");
                    }
                    advance();
                    if (item.typ() != ItemType.STRING) {
                        unexpected("label set", "string");
                    }
                    String value = unquoteString(item.val());
                    advance();
                    out.add(new Label(name, value));
                } else if (item.typ() == ItemType.STRING) {
                    String name = unquoteString(item.val());
                    advance();
                    if (item.typ() == ItemType.EQL) {
                        advance();
                        if (item.typ() != ItemType.STRING) {
                            unexpected("label set", "string");
                        }
                        String value = unquoteString(item.val());
                        advance();
                        out.add(new Label(name, value));
                    } else {
                        // string_identifier 单独出现：指标名
                        out.add(new Label(Labels.METRIC_NAME, name));
                    }
                } else {
                    unexpected("label set", "identifier or \"}\"");
                }
                if (item.typ() == ItemType.COMMA) {
                    advance();
                    if (item.typ() == ItemType.RIGHT_BRACE) {
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        if (item.typ() != ItemType.RIGHT_BRACE) {
            unexpected("label set", "\",\" or \"}\"");
        }
        advance();
        return Labels.newLabels(out);
    }

    // ====================================================================
    // 词法单元类别（.y 各 token 列表）
    // ====================================================================

    /** metric_identifier（.y 837 行）：可作指标名的词法单元（含聚合器/关键字等）。 */
    private static boolean isMetricIdentifier(ItemType t) {
        return switch (t) {
            case AVG, BOTTOMK, BY, COUNT, COUNT_VALUES, FILL, FILL_LEFT, FILL_RIGHT, GROUP, IDENTIFIER,
                    LAND, LOR, LUNLESS, MAX, METRIC_IDENTIFIER, MIN, OFFSET, QUANTILE, STDDEV, STDVAR,
                    SUM, TOPK, WITHOUT, START, END, LIMITK, LIMIT_RATIO, STEP, RANGE, ANCHORED, SMOOTHED,
                    MAX_OF, MIN_OF -> true;
            default -> false;
        };
    }

    /** maybe_label（.y 1095 行）：分组括号内可作标签名的词法单元。 */
    private static boolean isMaybeLabel(ItemType t) {
        return switch (t) {
            case AVG, BOOL, BOTTOMK, BY, COUNT, COUNT_VALUES, GROUP, GROUP_LEFT, GROUP_RIGHT, FILL,
                    FILL_LEFT, FILL_RIGHT, IDENTIFIER, IGNORING, LAND, LOR, LUNLESS, MAX,
                    METRIC_IDENTIFIER, MIN, OFFSET, ON, QUANTILE, STDDEV, STDVAR, SUM, TOPK, START, END,
                    ATAN2, LIMITK, LIMIT_RATIO, STEP, RANGE, ANCHORED, SMOOTHED, MAX_OF, MIN_OF -> true;
            default -> false;
        };
    }

    private static boolean isMatchOp(ItemType t) {
        return t == ItemType.EQL || t == ItemType.NEQ || t == ItemType.EQL_REGEX || t == ItemType.NEQ_REGEX;
    }

    /** duration 表达式可由此词法单元起始（.y duration_expr 各备选的 FIRST 集）。 */
    private static boolean canStartDuration(ItemType t) {
        return t == ItemType.NUMBER || t == ItemType.DURATION || t == ItemType.ADD || t == ItemType.SUB
                || t == ItemType.STEP || t == ItemType.RANGE || t == ItemType.MAX_OF
                || t == ItemType.MIN_OF || t == ItemType.LEFT_PAREN;
    }

    private static BinaryOp binaryOpOf(ItemType t) {
        return switch (t) {
            case ADD -> BinaryOp.ADD;
            case SUB -> BinaryOp.SUB;
            case MUL -> BinaryOp.MUL;
            case DIV -> BinaryOp.DIV;
            case MOD -> BinaryOp.MOD;
            case POW -> BinaryOp.POW;
            case ATAN2 -> BinaryOp.ATAN2;
            case EQLC -> BinaryOp.EQLC;
            case NEQ -> BinaryOp.NEQ;
            case GTR -> BinaryOp.GT;
            case LSS -> BinaryOp.LT;
            case GTE -> BinaryOp.GTE;
            case LTE -> BinaryOp.LTE;
            case TRIM_UPPER -> BinaryOp.TRIM_UPPER;
            case TRIM_LOWER -> BinaryOp.TRIM_LOWER;
            case LAND -> BinaryOp.LAND;
            case LOR -> BinaryOp.LOR;
            case LUNLESS -> BinaryOp.LUNLESS;
            default -> null;
        };
    }

    private static int binaryPrec(ItemType t) {
        return switch (t) {
            case LOR -> PREC_LOR;
            case LAND, LUNLESS -> PREC_LAND;
            case EQLC, GTE, GTR, LSS, LTE, NEQ, TRIM_UPPER, TRIM_LOWER -> PREC_CMP;
            case ADD, SUB -> PREC_ADDSUB;
            case MUL, DIV, MOD, ATAN2 -> PREC_MULDIV;
            case POW -> PREC_POW;
            default -> 0;
        };
    }

    /** 时长二元运算（.y 无独立优先级声明，沿用主优先级表中相应算子的级别）。 */
    private static DurationOp durationOpOf(ItemType t) {
        return switch (t) {
            case ADD -> DurationOp.ADD;
            case SUB -> DurationOp.SUB;
            case MUL -> DurationOp.MUL;
            case DIV -> DurationOp.DIV;
            case MOD -> DurationOp.MOD;
            case POW -> DurationOp.POW;
            default -> null;
        };
    }

    private static int durationPrec(ItemType t) {
        return switch (t) {
            case ADD, SUB -> PREC_ADDSUB;
            case MUL, DIV, MOD -> PREC_MULDIV;
            case POW -> PREC_POW;
            default -> 0;
        };
    }

    private static DurationOp durationPreprocOp(Item fn) {
        return fn.typ() == ItemType.STEP ? DurationOp.STEP : DurationOp.RANGE;
    }

    private static DurationOp maxOfMinOfOp(Item fn) {
        return fn.typ() == ItemType.MAX_OF ? DurationOp.MAX_OF : DurationOp.MIN_OF;
    }

    private static AggregateOp toAggregateOp(ItemType t) {
        return switch (t) {
            case AVG -> AggregateOp.AVG;
            case BOTTOMK -> AggregateOp.BOTTOMK;
            case COUNT -> AggregateOp.COUNT;
            case COUNT_VALUES -> AggregateOp.COUNT_VALUES;
            case GROUP -> AggregateOp.GROUP;
            case MAX -> AggregateOp.MAX;
            case MIN -> AggregateOp.MIN;
            case QUANTILE -> AggregateOp.QUANTILE;
            case STDDEV -> AggregateOp.STDDEV;
            case STDVAR -> AggregateOp.STDVAR;
            case SUM -> AggregateOp.SUM;
            case TOPK -> AggregateOp.TOPK;
            case LIMITK -> AggregateOp.LIMITK;
            case LIMIT_RATIO -> AggregateOp.LIMIT_RATIO;
            default -> throw new IllegalStateException("not an aggregator: " + t);
        };
    }
}

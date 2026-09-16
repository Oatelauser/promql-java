package io.github.oatelauser.promql.lexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.oatelauser.promql.util.GoStrings;

/**
 * 词法单元类型，对应 Go {@code parser/lex.go} 的 {@code ItemType}（常量值
 * 来自 {@code generated_parser.y.go} 的 goyacc 编号）。
 *
 * <p>{@link #code()} 保留 Go 的整型编号（如 ADD=57383），供区间判定与
 * {@code <Item N>} 兜底渲染使用；判定方法（{@link #isOperator()} 等）与
 * Go 一样基于哨兵编号的开区间比较。
 *
 * <p><b>快照裁剪（Q11）</b>：序列描述（series description）路径不移植，
 * 因此 OpenHist/CloseHist、histogramDesc 组、counterResetHints 组、
 * startSymbols 组的词条未纳入；表达式/指标选择器解析不会产生它们。
 * 保留 BLANK/SPACE/TIMES/SEMICOLON 词条仅为编号与渲染完整性（表达式
 * 上下文中不会被产出）。
 */
public enum ItemType {

    // ---- 第一组：基础词法单元（goyacc 57346..57368）----
    EQL(57346),
    BLANK(57347),
    COLON(57348),
    COMMA(57349),
    COMMENT(57350),
    DURATION(57351),
    EOF(57352),
    ERROR(57353),
    IDENTIFIER(57354),
    LEFT_BRACE(57355),
    LEFT_BRACKET(57356),
    LEFT_PAREN(57357),
    METRIC_IDENTIFIER(57360),
    NUMBER(57361),
    RIGHT_BRACE(57362),
    RIGHT_BRACKET(57363),
    RIGHT_PAREN(57364),
    SEMICOLON(57365),
    SPACE(57366),
    STRING(57367),
    TIMES(57368),

    // ---- 第二组：运算符（含哨兵）----
    OPERATORS_START(57382),
    ADD(57383),
    DIV(57384),
    EQLC(57385),
    EQL_REGEX(57386),
    GTE(57387),
    GTR(57388),
    TRIM_UPPER(57389),
    TRIM_LOWER(57390),
    LAND(57391),
    LOR(57392),
    LSS(57393),
    LTE(57394),
    LUNLESS(57395),
    MOD(57396),
    MUL(57397),
    NEQ(57398),
    NEQ_REGEX(57399),
    POW(57400),
    SUB(57401),
    AT(57402),
    ATAN2(57403),
    OPERATORS_END(57404),

    // ---- 第三组：聚合运算符（含哨兵）----
    AGGREGATORS_START(57405),
    AVG(57406),
    BOTTOMK(57407),
    COUNT(57408),
    COUNT_VALUES(57409),
    GROUP(57410),
    MAX(57411),
    MIN(57412),
    QUANTILE(57413),
    STDDEV(57414),
    STDVAR(57415),
    SUM(57416),
    TOPK(57417),
    LIMITK(57418),
    LIMIT_RATIO(57419),
    AGGREGATORS_END(57420),

    // ---- 第四组：关键字（含哨兵）----
    KEYWORDS_START(57421),
    BOOL(57422),
    BY(57423),
    GROUP_LEFT(57424),
    GROUP_RIGHT(57425),
    FILL(57426),
    FILL_LEFT(57427),
    FILL_RIGHT(57428),
    IGNORING(57429),
    OFFSET(57430),
    SMOOTHED(57431),
    ANCHORED(57432),
    ON(57433),
    WITHOUT(57434),
    KEYWORDS_END(57435),

    /**
     * 时长表达式预处理器（快照新增；无 Go 侧区间判定方法，哨兵仅保编号）。
     */
    PREPROCESSOR_START(57436),
    START(57437),
    END(57438),
    STEP(57439),
    RANGE(57440),
    MAX_OF(57441),
    MIN_OF(57442),
    PREPROCESSOR_END(57443);

    /**
     * goyacc 编号（保留 Go 数值；与 {@link #ordinal()} 无关）。
     */
    private final int code;

    ItemType(int code) {
        this.code = code;
    }

    /**
     * goyacc 编号。
     */
    public int code() {
        return code;
    }

    /**
     * 对应 Go {@code ItemType.IsOperator}：算术/比较/集合运算符。
     */
    public boolean isOperator() {
        return code > OPERATORS_START.code && code < OPERATORS_END.code;
    }

    /**
     * 对应 Go {@code ItemType.IsAggregator}：聚合运算符。
     */
    public boolean isAggregator() {
        return code > AGGREGATORS_START.code && code < AGGREGATORS_END.code;
    }

    /**
     * 对应 Go {@code ItemType.IsAggregatorWithParam}：带参数的聚合。
     */
    public boolean isAggregatorWithParam() {
        return this == TOPK || this == BOTTOMK || this == COUNT_VALUES
                || this == QUANTILE || this == LIMITK || this == LIMIT_RATIO;
    }

    /**
     * 对应 Go {@code ItemType.IsExperimentalAggregator}：受实验开关控制。
     */
    public boolean isExperimentalAggregator() {
        return this == LIMITK || this == LIMIT_RATIO;
    }

    /**
     * 对应 Go {@code ItemType.IsKeyword}：修饰关键字（不含聚合器/运算符）。
     */
    public boolean isKeyword() {
        return code > KEYWORDS_START.code && code < KEYWORDS_END.code;
    }

    /**
     * 对应 Go {@code ItemType.IsComparisonOperator}。
     */
    public boolean isComparisonOperator() {
        switch (this) {
            case EQLC:
            case NEQ:
            case LTE:
            case LSS:
            case GTE:
            case GTR:
                return true;
            default:
                return false;
        }
    }

    /**
     * 对应 Go {@code ItemType.IsSetOperator}。
     */
    public boolean isSetOperator() {
        return this == LAND || this == LOR || this == LUNLESS;
    }

    /**
     * PromQL 全部关键字（含运算符、聚合器、修饰字、预处理器与特殊数字
     * inf/nan），对应 Go {@code key} map（{@code init()} 合并 inf/nan 后）。
     * 查找按小写进行（Go {@code strings.ToLower(word)}）。
     */
    private static final Map<String, ItemType> KEY = buildKey();

    private static Map<String, ItemType> buildKey() {
        Map<String, ItemType> m = new HashMap<>(64);
        // 运算符。
        m.put("and", LAND);
        m.put("or", LOR);
        m.put("unless", LUNLESS);
        m.put("atan2", ATAN2);
        // 聚合器。
        m.put("sum", SUM);
        m.put("avg", AVG);
        m.put("count", COUNT);
        m.put("min", MIN);
        m.put("max", MAX);
        m.put("group", GROUP);
        m.put("stddev", STDDEV);
        m.put("stdvar", STDVAR);
        m.put("topk", TOPK);
        m.put("bottomk", BOTTOMK);
        m.put("count_values", COUNT_VALUES);
        m.put("quantile", QUANTILE);
        m.put("limitk", LIMITK);
        m.put("limit_ratio", LIMIT_RATIO);
        // 关键字。
        m.put("offset", OFFSET);
        m.put("smoothed", SMOOTHED);
        m.put("anchored", ANCHORED);
        m.put("by", BY);
        m.put("without", WITHOUT);
        m.put("on", ON);
        m.put("ignoring", IGNORING);
        m.put("group_left", GROUP_LEFT);
        m.put("group_right", GROUP_RIGHT);
        m.put("fill", FILL);
        m.put("fill_left", FILL_LEFT);
        m.put("fill_right", FILL_RIGHT);
        m.put("bool", BOOL);
        // 预处理器。
        m.put("start", START);
        m.put("end", END);
        m.put("step", STEP);
        m.put("range", RANGE);
        m.put("max_of", MAX_OF);
        m.put("min_of", MIN_OF);
        // 特殊数字（Go init() 注入）。
        m.put("inf", NUMBER);
        m.put("nan", NUMBER);
        return m;
    }

    /**
     * 关键字查找（小写化后），对应 Go {@code key[strings.ToLower(word)]}。
     */
    public static ItemType keywordType(String word) {
        return KEY.get(word.toLowerCase(Locale.ROOT));
    }

    /**
     * 词法器可识别的全部关键字字符串（排序副本，Go 版本为 map 无序遍历）。
     */
    public static List<String> keywords() {
        List<String> result = new ArrayList<>(KEY.keySet());
        result.sort(String::compareTo);
        return result;
    }

    /**
     * 词法单元的默认字符串表示（对应 Go {@code ItemTypeStr} 表与
     * {@code ItemType.String()}），查无时渲染为 {@code <Item N>}。
     */
    private static final Map<ItemType, String> ITEM_TYPE_STR = buildItemTypeStr();

    private static Map<ItemType, String> buildItemTypeStr() {
        Map<ItemType, String> m = new HashMap<>(64);
        m.put(LEFT_PAREN, "(");
        m.put(RIGHT_PAREN, ")");
        m.put(LEFT_BRACE, "{");
        m.put(RIGHT_BRACE, "}");
        m.put(LEFT_BRACKET, "[");
        m.put(RIGHT_BRACKET, "]");
        m.put(COMMA, ",");
        m.put(EQL, "=");
        m.put(COLON, ":");
        m.put(SEMICOLON, ";");
        m.put(BLANK, "_");
        m.put(TIMES, "x");
        m.put(SPACE, "<space>");
        m.put(SUB, "-");
        m.put(ADD, "+");
        m.put(MUL, "*");
        m.put(MOD, "%");
        m.put(DIV, "/");
        m.put(EQLC, "==");
        m.put(NEQ, "!=");
        m.put(LTE, "<=");
        m.put(LSS, "<");
        m.put(GTE, ">=");
        m.put(GTR, ">");
        m.put(TRIM_UPPER, "</");
        m.put(TRIM_LOWER, ">/");
        m.put(EQL_REGEX, "=~");
        m.put(NEQ_REGEX, "!~");
        m.put(POW, "^");
        m.put(AT, "@");
        // Go init()：关键字表整体并入——但 init() 是先把关键字并入 ItemTypeStr、
        // 之后才追加 key["inf"/"nan"] = NUMBER，故符号表【不含】NUMBER。
        // 若照单全收，NUMBER.toString() 会变成 "nan"，Item.desc() 随之丢失
        // "number" 前缀（unexpected number "1" 误作 unexpected "1"）。
        // 差分模糊语料首次接入即抓出（fuzz_diff_cases.tsv 行 172 起 337 条分歧的根因之一）。
        for (Map.Entry<String, ItemType> e : KEY.entrySet()) {
            if (e.getValue() == NUMBER) {
                continue;
            }
            m.putIfAbsent(e.getValue(), e.getKey());
        }
        return m;
    }

    @Override
    public String toString() {
        String s = ITEM_TYPE_STR.get(this);
        if (s != null) {
            return s;
        }
        return "<Item " + code + ">";
    }

    /**
     * 类型的人类可读描述（对应 Go {@code ItemType.desc()}），用于解析错误
     * 文本（如 {@code unexpected identifier}）；无专名的类型渲染为带引号的
     * {@link #toString()} 形式。
     */
    public String desc() {
        switch (this) {
            case ERROR:
                return "error";
            case EOF:
                return "end of input";
            case COMMENT:
                return "comment";
            case IDENTIFIER:
                return "identifier";
            case METRIC_IDENTIFIER:
                return "metric identifier";
            case STRING:
                return "string";
            case NUMBER:
                return "number";
            case DURATION:
                return "duration";
            default:
                return GoStrings.quote(toString());
        }
    }
}

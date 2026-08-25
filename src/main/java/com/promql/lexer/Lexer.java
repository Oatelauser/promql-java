package com.promql.lexer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.promql.util.GoStrings;

/**
 * PromQL 词法器，对应 Go {@code parser/lex.go} 的 {@code Lexer} 状态机
 * （{@code stateFn} 逐函数移植；Go 的函数值用 {@link StateFn} 方法引用
 * 表达）。
 *
 * <p><b>裁剪（Q11）</b>：序列描述路径不移植——{@code lexHistogram}、
 * {@code lexHistogramDescriptor}、{@code lexBuckets}、
 * {@code lexValueSequence} 及 seriesDesc/histogramState 状态位删除；
 * 其余状态（含快照新增的 {@code lexDurationExpr} 时长表达式与 fill
 * 上下文关键字）逐行对照保留，错误文本与 Go 完全一致（英文，Q12）。
 *
 * <p><b>位置语义分歧</b>：Go 的 {@code posrange.Pos} 是输入串的
 * <strong>字节</strong>偏移；本库为 UTF-16 码元偏移（ASCII 输入下一致，
 * 见 PORTING.md）。
 */
public final class Lexer {

    /** 输入结束哨兵（Go 的 {@code eof = -1}）。 */
    static final int EOF = -1;

    /** 行注释起始符（Go {@code lineComment = "#"}）。 */
    private static final String LINE_COMMENT = "#";

    /** 时长关键字表（Go {@code durationKeywordTokens}）。 */
    private static final Map<String, ItemType> DURATION_KEYWORDS = buildDurationKeywords();

    private static Map<String, ItemType> buildDurationKeywords() {
        Map<String, ItemType> m = new HashMap<>(8);
        m.put("step", ItemType.STEP);
        m.put("range", ItemType.RANGE);
        m.put("max_of", ItemType.MAX_OF);
        m.put("min_of", ItemType.MIN_OF);
        return m;
    }

    /** 状态函数（Go 的 {@code stateFn}；各状态的实例方法引用）。 */
    private interface StateFn {
        StateFn run();
    }

    private final String input;   // 被扫描的输入串。
    private StateFn state;        // 下一个进入的词法状态。
    private int pos;              // 输入中的当前位置。
    private int start;            // 当前词法单元的起点。
    private int width;            // 最近一次 next() 越过的码元数。
    private int lastPos;          // 最近一次产出单元的位置。
    private Item item;            // 最近一次产出的单元（Go 的 *itemp）。
    private boolean scannedItem;  // 每产出一个单元置位。

    private int parenDepth;       // ( ) 嵌套深度。
    private boolean braceOpen;    // { 是否打开。
    private boolean bracketOpen;  // [ 是否打开。
    private boolean gotColon;     // [ 打开后是否已出现 ':'。
    private boolean gotDuration;  // [ 打开后是否已出现时长。
    private int stringOpen;       // 当前字符串的引号字符。

    /**
     * 创建词法器（Go {@code Lex}），初始状态 {@code lexStatements}。
     */
    public Lexer(String input) {
        this.input = input;
        this.state = this::lexStatements;
    }

    /** 被扫描的输入串（解析器诊断用）。 */
    public String input() {
        return input;
    }

    /** 当前词法单元的起点（Go {@code p.lex.start}，词法错误的区间计算用）。 */
    public int start() {
        return start;
    }

    // ------------------------------------------------------------------
    // 基本操作：next / peek / backup / accept / emit / errorf
    // ------------------------------------------------------------------

    /** 读入下一个 rune（码点）；越界返回 {@link #EOF} 且不前进。 */
    private int next() {
        if (pos >= input.length()) {
            width = 0;
            return EOF;
        }
        char c1 = input.charAt(pos);
        if (Character.isHighSurrogate(c1) && pos + 1 < input.length()) {
            char c2 = input.charAt(pos + 1);
            if (Character.isLowSurrogate(c2)) {
                width = 2;
                pos += 2;
                return Character.toCodePoint(c1, c2);
            }
        }
        width = 1;
        pos += 1;
        return c1;
    }

    /** 窥视但不消费下一个 rune。 */
    private int peek() {
        int r = next();
        backup();
        return r;
    }

    /** 回退一个 rune；每次 next() 后至多调用一次。 */
    private void backup() {
        pos -= width;
    }

    /** 若下一个 rune 在集合内则消费之。 */
    private boolean accept(String valid) {
        if (valid.indexOf(next()) >= 0) {
            return true;
        }
        backup();
        return false;
    }

    /** 窥视下一个 rune 是否在集合内（不消费）。 */
    private boolean is(String valid) {
        return valid.indexOf(peek()) >= 0;
    }

    /** 连续消费集合内的 rune。 */
    private void acceptRun(String valid) {
        while (valid.indexOf(next()) >= 0) {
            // 消费。
        }
        backup();
    }

    /** 产出词法单元并推进起点。 */
    private void emit(ItemType t) {
        item = new Item(t, start, input.substring(start, pos));
        start = pos;
        scannedItem = true;
    }

    /** 跳过当前位置之前的待处理输入。 */
    private void ignore() {
        start = pos;
    }

    /** 产出错误单元并返回 null 终止扫描（Go {@code errorf}）。 */
    private StateFn errorf(String msg) {
        item = new Item(ItemType.ERROR, start, msg);
        scannedItem = true;
        return null;
    }

    /** 产出下一个词法单元（Go {@code NextItem}；Java 以返回值替代出参）。 */
    public Item nextItem() {
        scannedItem = false;
        if (state != null) {
            while (!scannedItem) {
                state = state.run();
            }
        } else {
            emit(ItemType.EOF);
        }
        lastPos = item.pos();
        return item;
    }

    // ------------------------------------------------------------------
    // 顶层状态 lexStatements
    // ------------------------------------------------------------------

    private StateFn lexStatements() {
        if (braceOpen) {
            return this::lexInsideBraces;
        }
        if (input.startsWith(LINE_COMMENT, pos)) {
            return this::lexLineComment;
        }

        int r = next();
        if (r == EOF) {
            if (parenDepth != 0) {
                return errorf("unclosed left parenthesis");
            }
            if (bracketOpen) {
                return errorf("unclosed left bracket");
            }
            emit(ItemType.EOF);
            return null;
        }
        if (r == ',') {
            emit(ItemType.COMMA);
        } else if (isSpace(r)) {
            return this::lexSpace;
        } else if (r == '*') {
            emit(ItemType.MUL);
        } else if (r == '/') {
            emit(ItemType.DIV);
        } else if (r == '%') {
            emit(ItemType.MOD);
        } else if (r == '+') {
            emit(ItemType.ADD);
        } else if (r == '-') {
            emit(ItemType.SUB);
        } else if (r == '^') {
            emit(ItemType.POW);
        } else if (r == '=') {
            int t = peek();
            if (t == '=') {
                next();
                emit(ItemType.EQLC);
            } else if (t == '~') {
                return errorf(String.format(Locale.ROOT,
                        "unexpected character after '=': %s", GoStrings.quoteRune(t)));
            } else {
                emit(ItemType.EQL);
            }
        } else if (r == '!') {
            int t = next();
            if (t != '=') {
                return errorf(String.format(Locale.ROOT,
                        "unexpected character after '!': %s", GoStrings.quoteRune(t)));
            }
            emit(ItemType.NEQ);
        } else if (r == '<') {
            int t = peek();
            if (t == '=') {
                next();
                emit(ItemType.LTE);
            } else if (t == '/') {
                next();
                emit(ItemType.TRIM_UPPER);
            } else {
                emit(ItemType.LSS);
            }
        } else if (r == '>') {
            int t = peek();
            if (t == '=') {
                next();
                emit(ItemType.GTE);
            } else if (t == '/') {
                next();
                emit(ItemType.TRIM_LOWER);
            } else {
                emit(ItemType.GTR);
            }
        } else if (isDigit(r) || (r == '.' && isDigit(peek()))) {
            backup();
            return this::lexNumberOrDuration;
        } else if (r == '"' || r == '\'') {
            stringOpen = r;
            return this::lexString;
        } else if (r == '`') {
            stringOpen = r;
            return this::lexRawString;
        } else if (isAlpha(r) || r == ':') {
            if (!bracketOpen) {
                backup();
                return this::lexKeywordOrIdentifier;
            }
            if (r == ':') {
                if (gotColon) {
                    return errorf(String.format(Locale.ROOT,
                            "unexpected colon %s", GoStrings.quoteRune(r)));
                }
                emit(ItemType.COLON);
                gotColon = true;
                return this::lexStatements;
            }
            if (isDurationKeywordStartChar(r) && scanDurationKeyword()) {
                return this::lexStatements;
            }
            return errorf(String.format(Locale.ROOT,
                    "unexpected character: %s, expected %s", GoStrings.quoteRune(r), GoStrings.quoteRune(':')));
        } else if (r == '(') {
            emit(ItemType.LEFT_PAREN);
            parenDepth++;
            return this::lexStatements;
        } else if (r == ')') {
            emit(ItemType.RIGHT_PAREN);
            parenDepth--;
            if (parenDepth < 0) {
                return errorf(String.format(Locale.ROOT,
                        "unexpected right parenthesis %s", GoStrings.quoteRune(r)));
            }
            return this::lexStatements;
        } else if (r == '{') {
            emit(ItemType.LEFT_BRACE);
            braceOpen = true;
            return this::lexInsideBraces;
        } else if (r == '[') {
            if (bracketOpen) {
                return errorf(String.format(Locale.ROOT,
                        "unexpected left bracket %s", GoStrings.quoteRune(r)));
            }
            gotColon = false;
            emit(ItemType.LEFT_BRACKET);
            if (isSpace(peek())) {
                skipSpaces();
            }
            bracketOpen = true;
            return this::lexDurationExpr;
        } else if (r == ']') {
            if (!bracketOpen) {
                return errorf(String.format(Locale.ROOT,
                        "unexpected right bracket %s", GoStrings.quoteRune(r)));
            }
            emit(ItemType.RIGHT_BRACKET);
            bracketOpen = false;
        } else if (r == '@') {
            emit(ItemType.AT);
        } else {
            return errorf(String.format(Locale.ROOT,
                    "unexpected character: %s", GoStrings.quoteRune(r)));
        }
        return this::lexStatements;
    }

    // ------------------------------------------------------------------
    // 大括号内部 lexInsideBraces
    // ------------------------------------------------------------------

    private StateFn lexInsideBraces() {
        if (input.startsWith(LINE_COMMENT, pos)) {
            return this::lexLineComment;
        }

        int r = next();
        if (r == EOF) {
            return errorf("unexpected end of input inside braces");
        }
        if (isSpace(r)) {
            return this::lexSpace;
        }
        if (isAlpha(r)) {
            backup();
            return this::lexIdentifier;
        }
        if (r == ',') {
            emit(ItemType.COMMA);
        } else if (r == '"' || r == '\'') {
            stringOpen = r;
            return this::lexString;
        } else if (r == '`') {
            stringOpen = r;
            return this::lexRawString;
        } else if (r == '=') {
            if (next() == '~') {
                emit(ItemType.EQL_REGEX);
            } else {
                backup();
                emit(ItemType.EQL);
            }
        } else if (r == '!') {
            int nr = next();
            if (nr == '~') {
                emit(ItemType.NEQ_REGEX);
            } else if (nr == '=') {
                emit(ItemType.NEQ);
            } else {
                return errorf(String.format(Locale.ROOT,
                        "unexpected character after '!' inside braces: %s", GoStrings.quoteRune(nr)));
            }
        } else if (r == '{') {
            return errorf(String.format(Locale.ROOT,
                    "unexpected left brace %s", GoStrings.quoteRune(r)));
        } else if (r == '}') {
            emit(ItemType.RIGHT_BRACE);
            braceOpen = false;
            // seriesDesc 裁剪：恒回到语句状态。
            return this::lexStatements;
        } else {
            return errorf(String.format(Locale.ROOT,
                    "unexpected character inside braces: %s", GoStrings.quoteRune(r)));
        }
        return this::lexInsideBraces;
    }

    // ------------------------------------------------------------------
    // 字符串
    // ------------------------------------------------------------------

    /** 扫描带引号字符串；起始引号已消费（Go {@code lexString}）。 */
    private StateFn lexString() {
        while (true) {
            int r = next();
            if (r == '\\') {
                return this::lexEscape;
            }
            if (isInvalidRune(r)) {
                errorf("invalid UTF-8 rune");
                return this::lexString;
            }
            if (r == EOF || r == '\n') {
                return errorf("unterminated quoted string");
            }
            if (r == stringOpen) {
                break;
            }
        }
        emit(ItemType.STRING);
        return this::lexStatements;
    }

    /** 扫描原始字符串；起始反引号已消费（Go {@code lexRawString}）。 */
    private StateFn lexRawString() {
        while (true) {
            int r = next();
            if (isInvalidRune(r)) {
                errorf("invalid UTF-8 rune");
                return this::lexRawString;
            }
            if (r == EOF) {
                errorf("unterminated raw string");
                return this::lexRawString;
            }
            if (r == stringOpen) {
                break;
            }
        }
        emit(ItemType.STRING);
        return this::lexStatements;
    }

    /**
     * 扫描字符串转义序列；起始反斜杠已消费（Go {@code lexEscape}，改编自
     * Go 标准库 go/scanner）。
     */
    private StateFn lexEscape() {
        int n = 0;
        int base = 0;
        int maxVal = 0;

        int ch = next();
        if (ch == 'a' || ch == 'b' || ch == 'f' || ch == 'n' || ch == 'r'
                || ch == 't' || ch == 'v' || ch == '\\' || ch == stringOpen) {
            return this::lexString;
        }
        if (ch >= '0' && ch <= '7') {
            n = 3;
            base = 8;
            maxVal = 255;
        } else if (ch == 'x') {
            ch = next();
            n = 2;
            base = 16;
            maxVal = 255;
        } else if (ch == 'u') {
            ch = next();
            n = 4;
            base = 16;
            maxVal = Character.MAX_CODE_POINT;
        } else if (ch == 'U') {
            ch = next();
            n = 8;
            base = 16;
            maxVal = Character.MAX_CODE_POINT;
        } else if (ch == EOF) {
            errorf("escape sequence not terminated");
            return this::lexString;
        } else {
            errorf(String.format(Locale.ROOT,
                    "unknown escape sequence %s", GoStrings.sharpU(ch)));
            return this::lexString;
        }

        long x = 0;
        while (n > 0) {
            int d = digitVal(ch);
            if (d >= base) {
                if (ch == EOF) {
                    errorf("escape sequence not terminated");
                    return this::lexString;
                }
                errorf(String.format(Locale.ROOT,
                        "illegal character %s in escape sequence", GoStrings.sharpU(ch)));
                return this::lexString;
            }
            x = x * base + d;
            n--;

            // 不越过最后一个 rune。
            if (n > 0) {
                ch = next();
            }
        }

        if (x > maxVal || (x >= 0xD800 && x < 0xE000)) {
            errorf("escape sequence is an invalid Unicode code point");
        }
        return this::lexString;
    }

    /** rune 的数字值；非法时返回 16（大于任何合法数字，Go {@code digitVal}）。 */
    static int digitVal(int ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        return 16;
    }

    // ------------------------------------------------------------------
    // 空白与注释
    // ------------------------------------------------------------------

    private StateFn lexSpace() {
        while (isSpace(peek())) {
            next();
        }
        ignore();
        return this::lexStatements;
    }

    /** 跳过连续空白并 ignore（Go {@code skipSpaces}）。 */
    private void skipSpaces() {
        while (isSpace(peek())) {
            next();
        }
        ignore();
    }

    private StateFn lexLineComment() {
        pos += LINE_COMMENT.length();
        for (int r = next(); !isEndOfLine(r) && r != EOF; r = next()) {
            // 消费。
        }
        backup();
        emit(ItemType.COMMENT);
        return this::lexStatements;
    }

    // ------------------------------------------------------------------
    // 数字与时长
    // ------------------------------------------------------------------

    private StateFn lexNumber() {
        if (!scanNumber()) {
            return errorf(String.format(Locale.ROOT,
                    "bad number syntax: %s", GoStrings.quote(input.substring(start, pos))));
        }
        emit(ItemType.NUMBER);
        return this::lexStatements;
    }

    /** 扫描数字或时长单元（Go {@code lexNumberOrDuration}）。 */
    private StateFn lexNumberOrDuration() {
        if (scanNumber()) {
            emit(ItemType.NUMBER);
            return this::lexStatements;
        }
        // 接下来必须是合法单位 + 非字母数字。
        if (acceptRemainingDuration()) {
            backup();
            emit(ItemType.DURATION);
            return this::lexStatements;
        }
        return errorf(String.format(Locale.ROOT,
                "bad number or duration syntax: %s", GoStrings.quote(input.substring(start, pos))));
    }

    /** 验证剩余输入构成时长（Go {@code acceptRemainingDuration}）。 */
    private boolean acceptRemainingDuration() {
        // 接下来的两个字符必须是合法时长。
        if (!accept("smhdwy")) {
            return false;
        }
        // 支持 ms。hs、ys 等坏单位在真正解析时长时才报错。
        accept("s");
        // 下一个字符可以是另一段数字，随后接单位。
        while (accept("0123456789")) {
            while (accept("0123456789")) {
                // 消费。
            }
            // y 不在列表中：y 必须始终位于时长最前。
            if (!accept("smhdw")) {
                return false;
            }
            // 支持 ms。
            accept("s");
        }
        return !isAlphaNumeric(next());
    }

    /**
     * 扫描各种进制的数字；扫描结果不保证是合法数字，由解析器兜底
     * （Go {@code scanNumber}）。seriesDesc 已裁剪（恒 false）。
     */
    private boolean scanNumber() {
        int initialPos = pos;
        // 十六进制使用独立数字集。
        String digitPattern = "0123456789";
        // Go: if !l.seriesDesc && l.accept("0") && l.accept("xX")
        // seriesDesc 恒 false，即十六进制前缀总是被识别。
        if (accept("0") && accept("xX")) {
            accept("_"); // 如 0X_1FFFP-16
            digitPattern = "0123456789abcdefABCDEF";
        }
        final String dotPattern = ".";
        final String exponentPattern = "eE";
        final String underscorePattern = "_";
        // 反模式：相应 rune 之后不可紧跟的字符集合。
        final String dotAntiPattern = "_.";
        final String exponentAntiPattern = "._eE"; // 及 EOL。
        final String underscoreAntiPattern = "._eE"; // 及 EOL。
        // 所有数字符合前缀模式：[.][d][d._eE]*
        accept(dotPattern);
        accept(digitPattern);
        boolean dotConsumed = false;
        boolean exponentConsumed = false;
        while (is(join(digitPattern, dotPattern, underscorePattern, exponentPattern))) {
            // "." 不可重复。
            if (is(dotPattern) && dotConsumed) {
                accept(dotPattern);
                return false;
            }
            // "eE" 不可重复。
            if (is(exponentPattern) && exponentConsumed) {
                accept(exponentPattern);
                return false;
            }
            // 处理点号。
            if (accept(dotPattern)) {
                dotConsumed = true;
                if (accept(dotAntiPattern)) {
                    return false;
                }
                // 十六进制不允许小数位。
                if (digitPattern.length() > 10) {
                    return false;
                }
                continue;
            }
            // 处理指数。
            if (accept(exponentPattern)) {
                exponentConsumed = true;
                accept("+-");
                if (accept(exponentAntiPattern) || peek() == EOF) {
                    return false;
                }
                continue;
            }
            // 处理下划线。
            if (accept(underscorePattern)) {
                if (accept(underscoreAntiPattern) || peek() == EOF) {
                    return false;
                }
                continue;
            }
            // 处理结尾数字（循环前已消费一位）。
            acceptRun(digitPattern);
        }
        // 空串不是合法数字。
        if (pos == initialPos) {
            return false;
        }
        // 后随字符不可是字母数字。
        // Go: if !l.seriesDesc && isAlphaNumeric(l.peek()) —— seriesDesc 恒
        // false。但 seriesDesc 下被接受的 'x'（如 1xhexval...）路径随裁剪删除。
        return !isAlphaNumeric(peek());
    }

    private static String join(String a, String b, String c, String d) {
        return a + b + c + d;
    }

    // ------------------------------------------------------------------
    // 标识符与关键字
    // ------------------------------------------------------------------

    /** 扫描字母数字标识符；下一字符已知是字母（Go {@code lexIdentifier}）。 */
    private StateFn lexIdentifier() {
        while (isAlphaNumeric(next())) {
            // 吸收。
        }
        backup();
        emit(ItemType.IDENTIFIER);
        return this::lexStatements;
    }

    /**
     * 扫描可含冒号的标识符；若是关键字则产出关键字单元
     * （Go {@code lexKeywordOrIdentifier}）。fill/fill_left/fill_right 仅在
     * 后随 '(' 时视为关键字，从而允许它们作为指标名。
     */
    private StateFn lexKeywordOrIdentifier() {
        while (true) {
            int r = next();
            if (isAlphaNumeric(r) || r == ':') {
                // 吸收。
                continue;
            }
            backup();
            String word = input.substring(start, pos);
            ItemType kw = ItemType.keywordType(word);
            if (kw != null) {
                if (kw == ItemType.FILL || kw == ItemType.FILL_LEFT || kw == ItemType.FILL_RIGHT) {
                    if (!peekFollowedByLeftParen()) {
                        emit(ItemType.IDENTIFIER);
                        break;
                    }
                }
                emit(kw);
            } else if (word.indexOf(':') < 0) {
                emit(ItemType.IDENTIFIER);
            } else {
                emit(ItemType.METRIC_IDENTIFIER);
            }
            break;
        }
        // seriesDesc 裁剪：恒回到语句状态。
        return this::lexStatements;
    }

    /** 检查下一个非空白字符是否为 '('（Go {@code peekFollowedByLeftParen}）。 */
    private boolean peekFollowedByLeftParen() {
        int p = pos;
        while (true) {
            if (p >= input.length()) {
                return false;
            }
            int r = input.codePointAt(p);
            if (!isSpace(r)) {
                return r == '(';
            }
            p += Character.charCount(r);
        }
    }

    // ------------------------------------------------------------------
    // 时长表达式（快照新增）
    // ------------------------------------------------------------------

    /** 时长关键字起始字符集合 {s, r, m}（Go {@code isDurationKeywordStartChar}）。 */
    static boolean isDurationKeywordStartChar(int r) {
        int lc = Character.toLowerCase(r);
        return lc == 's' || lc == 'r' || lc == 'm';
    }

    /** 扫描时长关键字；命中产出对应单元（Go {@code scanDurationKeyword}）。 */
    private boolean scanDurationKeyword() {
        while (true) {
            int r = next();
            if (isAlpha(r)) {
                // 吸收。
                continue;
            }
            backup();
            String word = input.substring(start, pos).toLowerCase(Locale.ROOT);
            ItemType tok = DURATION_KEYWORDS.get(word);
            if (tok != null) {
                emit(tok);
                return true;
            }
            return false;
        }
    }

    /**
     * 扫描方括号内的时长算术表达式（Go {@code lexDurationExpr}，快照新增；
     * 供 {@code [range:step]} 与时长表达式实验特性使用）。
     */
    private StateFn lexDurationExpr() {
        int r = next();
        if (r == EOF) {
            return errorf("unexpected end of input in duration expression");
        }
        if (r == ']') {
            emit(ItemType.RIGHT_BRACKET);
            bracketOpen = false;
            gotColon = false;
            return this::lexStatements;
        }
        if (r == ':') {
            emit(ItemType.COLON);
            if (!gotDuration) {
                return errorf("unexpected colon before duration in duration expression");
            }
            if (gotColon) {
                return errorf("unexpected repeated colon in duration expression");
            }
            gotColon = true;
            return this::lexDurationExpr;
        }
        if (r == '(') {
            emit(ItemType.LEFT_PAREN);
            parenDepth++;
            return this::lexDurationExpr;
        }
        if (r == ')') {
            emit(ItemType.RIGHT_PAREN);
            parenDepth--;
            if (parenDepth < 0) {
                return errorf(String.format(Locale.ROOT,
                        "unexpected right parenthesis %s", GoStrings.quoteRune(r)));
            }
            return this::lexDurationExpr;
        }
        if (isSpace(r)) {
            skipSpaces();
            return this::lexDurationExpr;
        }
        if (r == '+') {
            emit(ItemType.ADD);
            return this::lexDurationExpr;
        }
        if (r == '-') {
            emit(ItemType.SUB);
            return this::lexDurationExpr;
        }
        if (r == '*') {
            emit(ItemType.MUL);
            return this::lexDurationExpr;
        }
        if (r == '/') {
            emit(ItemType.DIV);
            return this::lexDurationExpr;
        }
        if (r == '%') {
            emit(ItemType.MOD);
            return this::lexDurationExpr;
        }
        if (r == '^') {
            emit(ItemType.POW);
            return this::lexDurationExpr;
        }
        if (r == ',') {
            emit(ItemType.COMMA);
            return this::lexDurationExpr;
        }
        if (isDurationKeywordStartChar(r)) {
            if (scanDurationKeyword()) {
                return this::lexDurationExpr;
            }
            return errorf(String.format(Locale.ROOT,
                    "unexpected character in duration expression: %s", GoStrings.quoteRune(r)));
        }
        if (isDigit(r) || (r == '.' && isDigit(peek()))) {
            backup();
            gotDuration = true;
            return this::lexNumberOrDuration;
        }
        return errorf(String.format(Locale.ROOT,
                "unexpected character in duration expression: %s", GoStrings.quoteRune(r)));
    }

    // ------------------------------------------------------------------
    // 回溯辅助（解析器专用）
    // ------------------------------------------------------------------

    /**
     * 向前寻找上一个右括号（Go {@code findPrevRightParen}，解析器读超前时
     * 找回位置用）。仅在字符串字面量之外使用；任何异常输入回退到
     * fallbackPos。
     */
    public int findPrevRightParen(int fallbackPos) {
        if (fallbackPos <= 0 || fallbackPos > input.length()
                || lastPos <= 0 || lastPos >= input.length()
                || input.charAt(lastPos) != ')') {
            return fallbackPos;
        }
        for (int i = lastPos - 1; i > 0; i--) {
            char c = input.charAt(i);
            if (c == ')') {
                return i + 1;
            }
            if (!isSpace(c)) {
                return fallbackPos;
            }
        }
        return fallbackPos;
    }

    // ------------------------------------------------------------------
    // 字符分类（Go lex.go 底部助手；刻意不使用 Unicode 类别判定）
    // ------------------------------------------------------------------

    /** 空白字符：空格、制表、换行、回车。 */
    public static boolean isSpace(int r) {
        return r == ' ' || r == '\t' || r == '\n' || r == '\r';
    }

    /** 行结束字符：回车或换行。 */
    public static boolean isEndOfLine(int r) {
        return r == '\r' || r == '\n';
    }

    /** 字母数字（含下划线）。 */
    public static boolean isAlphaNumeric(int r) {
        return isAlpha(r) || isDigit(r);
    }

    /**
     * ASCII 数字。刻意不使用 {@code Character.isDigit}：后者会把非拉丁
     * 数字也判为数字（上游 issue 939）。
     */
    public static boolean isDigit(int r) {
        return r >= '0' && r <= '9';
    }

    /** ASCII 字母或下划线。 */
    public static boolean isAlpha(int r) {
        return r == '_' || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z');
    }

    /** 孤立代理项（对应 Go 的 utf8.RuneError 情形）。 */
    private static boolean isInvalidRune(int r) {
        return r >= Character.MIN_SURROGATE && r <= Character.MAX_SURROGATE;
    }
}

package io.github.oatelauser.promql.util;

/**
 * Go {@code strconv} 字符串语义的移植：Quote（打印路径）与 Unquote（解析路径）。
 *
 * <p>PromQL 字符串字面量支持双引号、单引号（带转义）与反引号（原始字符串）。
 * 本类按 Go 规则逐字符处理，供 {@code LabelMatcher.toPromql()}、打印器
 * （StringLiteral）与解析器（unquoteString）共用。
 *
 * <p>注意：Java 没有 {@code \a}（响铃）与 {@code \v}（垂直制表）转义，本文件
 * 一律用 Unicode 转义 {@code \u0007} / {@code \u000B} 表示。
 */
public final class GoStrings {

    private GoStrings() {
    }

    /**
     * 对应 Go {@code strconv.Quote}：用双引号包裹并按 Go 转义规则转义
     * （不可打印字符转 hex-or-unicode escapes 或 \a\b\f\n\r\t\v 转义）。
     */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        appendQuoted(sb, s);
        sb.append('"');
        return sb.toString();
    }

    private static void appendQuoted(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\u0007' -> sb.append("\\a");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\u000B' -> sb.append("\\v");
                default -> {
                    if (c >= 0x20 && c < 0x7f) {
                        sb.append(c);
                    } else if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        // 非 ASCII：可打印 rune 保留字面（Go strconv.Quote 的行为，
                        // 如 Quote("日本語") 输出带引号的原文）；不可打印输出小写
                        // BMP 与增补面分别用反斜杠 u / U 作转义前缀（避免在注释里出现反斜杠加 u 的组合）。
                        int codePoint = s.codePointAt(i);
                        if (Character.isSupplementaryCodePoint(codePoint)) {
                            i++; // 跳过低代理
                        }
                        if (isPrintable(codePoint)) {
                            sb.appendCodePoint(codePoint);
                        } else if (codePoint < 0x10000) {
                            sb.append(String.format("\\u%04x", codePoint));
                        } else {
                            sb.append(String.format("\\U%08x", codePoint));
                        }
                    }
                }
            }
        }
    }

    /**
     * Go {@code unicode.IsPrint} 的近似：字母 L、记号 M、数字 N、标点 P、
     * 符号 S 五大类加 ASCII 空格视为可打印（控制、格式、代理、私用区、
     * 分隔符与未赋码位均不可打印）。Go 与 Java 的 Unicode 版本不同，极生僻
     * 码位可能判定不同（PORTING.md 已知分歧）。
     */
    static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.CONNECTOR_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /**
     * 对应 Go {@code strconv.QuoteRune}（fmt 的 {@code %q} 作用于 rune）：
     * 单引号包裹，可打印保留字面，否则转义。
     */
    public static String quoteRune(int cp) {
        StringBuilder sb = new StringBuilder(12);
        sb.append('\'');
        String esc = controlEscape(cp);
        if (esc != null) {
            sb.append(esc);
        } else if (cp == '\\') {
            sb.append("\\\\");
        } else if (cp == '\'') {
            sb.append("\\'");
        } else if (isPrintable(cp)) {
            sb.appendCodePoint(cp);
        } else if (cp < 0 || cp > 0x10FFFF) {
            // Go fmt 对无效 rune（含词法器的 eof=-1）按替换字符渲染。
            sb.appendCodePoint(0xFFFD);
        } else if (cp < 0x20 || cp == 0x7f) {
            sb.append(String.format("\\x%02x", cp));
        } else if (cp < 0x10000) {
            sb.append(String.format("\\u%04x", cp));
        } else {
            sb.append(String.format("\\U%08x", cp));
        }
        sb.append('\'');
        return sb.toString();
    }

    /**
     * Go 转义表中的 7 个控制字符（响铃、退格、换页、换行、回车、制表、垂直
     * 制表）。用数字码而非字符字面量，避免源文件出现裸控制字节。
     */
    private static String controlEscape(int cp) {
        switch (cp) {
            case 0x07:
                return "\\a";
            case '\b':
                return "\\b";
            case '\f':
                return "\\f";
            case '\n':
                return "\\n";
            case '\r':
                return "\\r";
            case '\t':
                return "\\t";
            case 0x0B:
                return "\\v";
            default:
                return null;
        }
    }

    /**
     * 对应 Go fmt 的 {@code %#U}：{@code U+0061 'a'}；不可打印时省略引号段
     * （如 {@code U+0001}）。引号与反斜杠不转义（实证 {@code U+0027 '''} 与
     * {@code U+005C '\'}），十六进制大写、至少 4 位。
     */
    public static String sharpU(int cp) {
        if (isPrintable(cp)) {
            return String.format("U+%04X '%s'", cp, new String(Character.toChars(cp)));
        }
        return String.format("U+%04X", cp);
    }

    /**
     * 对应 Prometheus {@code strutil.Unquote} / Go {@code strconv.Unquote}：
     * 剥离包裹引号并处理转义。反引号为原始字符串（无转义）。
     *
     * @return 解析后的字符串值
     * @throws IllegalArgumentException 无法解析时（对应 Go 的 error）
     */
    public static String unquote(String input) {
        if (input == null || input.length() < 2) {
            throw new IllegalArgumentException("invalid syntax");
        }
        char quote = input.charAt(0);
        if (quote != '"' && quote != '\'' && quote != '`') {
            throw new IllegalArgumentException("invalid syntax");
        }
        if (input.charAt(input.length() - 1) != quote) {
            throw new IllegalArgumentException("invalid syntax");
        }
        String body = input.substring(1, input.length() - 1);
        if (quote == '`') {
            if (body.indexOf('`') >= 0) {
                throw new IllegalArgumentException("invalid syntax");
            }
            if (body.indexOf('\r') >= 0) {
                // Go: 原始字符串中的 \r 被丢弃
                body = body.replace("\r", "");
            }
            return body;
        }
        return unescape(body, quote);
    }

    /**
     * 对应 Go {@code strconv.UnquoteChar} 的字符串体处理。
     */
    private static String unescape(String s, char quote) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') {
                throw new IllegalArgumentException("invalid syntax");
            }
            if (c != '\\') {
                sb.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= s.length()) {
                throw new IllegalArgumentException("invalid syntax");
            }
            char e = s.charAt(i);
            i++;
            switch (e) {
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'v' -> sb.append('\u000B');
                case '\\' -> sb.append('\\');
                case '\'' -> sb.append('\'');
                case '"' -> sb.append('"');
                case 'x', 'u', 'U' -> {
                    int width = e == 'x' ? 2 : (e == 'u' ? 4 : 8);
                    if (i + width > s.length()) {
                        throw new IllegalArgumentException("invalid syntax");
                    }
                    int value = 0;
                    for (int k = 0; k < width; k++) {
                        int d = Character.digit(s.charAt(i + k), 16);
                        if (d < 0) {
                            throw new IllegalArgumentException("invalid syntax");
                        }
                        value = (value << 4) | d;
                    }
                    i += width;
                    if (e == 'x') {
                        // \xNN：单字节值，按 Latin-1 落入 char
                        sb.append((char) value);
                    } else if (Character.isValidCodePoint(value)) {
                        sb.appendCodePoint(value);
                    } else {
                        throw new IllegalArgumentException("invalid syntax");
                    }
                }
                default -> {
                    if (e >= '0' && e <= '7') {
                        // 最多三位八进制
                        int value = e - '0';
                        int digits = 1;
                        while (digits < 3 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                            value = (value << 3) | (s.charAt(i) - '0');
                            i++;
                            digits++;
                        }
                        if (value > 255) {
                            throw new IllegalArgumentException("invalid syntax");
                        }
                        sb.append((char) value);
                    } else {
                        throw new IllegalArgumentException("invalid syntax");
                    }
                }
            }
        }
        return sb.toString();
    }
}

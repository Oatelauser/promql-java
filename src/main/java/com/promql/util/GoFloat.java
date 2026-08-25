package com.promql.util;

import java.util.Locale;

/**
 * Go {@code strconv} 浮点数格式化/解析语义的移植，供打印器（NumberLiteral、
 * fill 值、@ 时间戳）与解析器（number()）共用。
 *
 * <p>必须精确移植的原因：打印输出是 conformance 金标准的比对对象（P1 契约），
 * Java 自带的 {@code Double.toString} 与 Go 的最短表示在科学计数法阈值等
 * 处不同。
 */
public final class GoFloat {

    private GoFloat() {
    }

    /**
     * 对应 Go {@code strconv.FormatFloat(v, 'f', -1, 64)}：
     * 最短十进制、固定计数法（无指数）。NaN/Inf 特判。
     */
    public static String formatFloatF(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        String shortest = shortest(v);
        // 最短表示可能带指数（如 1e+21），'f' 格式需展开为固定计数法
        return expandToFixed(shortest, v);
    }

    /**
     * 对应 Go {@code %v}（即 'g' 最短格式）：最短十进制，必要时用科学计数法
     * （十进制指数 &lt; -4 或 &gt;= 6 时，Go strconv ftoa.go 最短格式取
     * {@code eprec = 6}）。用于 fill 修饰符值的打印。
     *
     * <p>阈值经本机 Go 1.25 实证：{@code 999999}→{@code 999999}（exp=5 定点）、
     * {@code 1000000}→{@code 1e+06}、{@code 1000001}→{@code 1.000001e+06}、
     * {@code 123456.789}→定点（与位数无关）、{@code 0.0001}→定点（exp=-4）、
     * {@code 1e-05}→科学。
     */
    public static String formatFloatG(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        String shortest = shortest(v);
        int decExp = decimalExponent(shortest, v);
        if (decExp < -4 || decExp >= 6) {
            return toScientific(shortest, v);
        }
        // 无指数时 'g' 与 'f' 一致，但 'g' 最短形式会去掉多余的无意义零
        return expandToFixed(shortest, v);
    }

    /**
     * 最短往返十进制表示（Java 的 Ryu 实现与 Go 同为最短算法）。
     */
    private static String shortest(double v) {
        if (v == 0.0) {
            return (Double.doubleToRawLongBits(v) < 0) ? "-0" : "0";
        }
        return Double.toString(v);
    }

    /**
     * 把最短表示展开为固定计数法（无指数部分）。
     */
    private static String expandToFixed(String shortest, double v) {
        int e = shortest.indexOf('E');
        if (e < 0) {
            return stripIntegralDotZero(shortest);
        }
        String mantissa = shortest.substring(0, e);
        int exp = Integer.parseInt(shortest.substring(e + 1));
        boolean neg = mantissa.startsWith("-");
        if (neg) {
            mantissa = mantissa.substring(1);
        }
        String digits = mantissa.replace(".", "");
        // Java 最短表示的尾零只可能来自整数值的强制 ".0"（如 1.0E-4），
        // 剥离后与 Go 的最短数字串一致（0.0001 而非 0.00010）。
        while (digits.length() > 1 && digits.charAt(digits.length() - 1) == '0') {
            digits = digits.substring(0, digits.length() - 1);
        }
        int pointPos = mantissa.indexOf('.') < 0 ? mantissa.length() : mantissa.indexOf('.');
        pointPos += exp;
        StringBuilder sb = new StringBuilder();
        if (pointPos <= 0) {
            sb.append("0.");
            sb.append("0".repeat(-pointPos));
            sb.append(digits);
        } else if (pointPos >= digits.length()) {
            sb.append(digits);
            sb.append("0".repeat(pointPos - digits.length()));
        } else {
            sb.append(digits, 0, pointPos).append('.').append(digits.substring(pointPos));
        }
        return neg ? "-" + sb : sb.toString();
    }

    /**
     * 去掉整数值的 {@code .0} 尾缀：{@code Double.toString(100.0) == "100.0"}，
     * 而 Go {@code FormatFloat(100, 'f', -1, 64) == "100"}。仅当小数部分
     * 全为零时剥离（最短表示不会有多组尾零）。
     */
    private static String stripIntegralDotZero(String s) {
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        for (int i = s.length() - 1; i > dot; i--) {
            if (s.charAt(i) != '0') {
                return s;
            }
        }
        return s.substring(0, dot);
    }

    /**
     * 'g' 科学计数法渲染（如 1e+06）。
     */
    private static String toScientific(String shortest, double v) {
        int e = shortest.indexOf('E');
        String mantissa = e < 0 ? shortest : shortest.substring(0, e);
        int exp = e < 0 ? 0 : Integer.parseInt(shortest.substring(e + 1));
        boolean neg = mantissa.startsWith("-");
        if (neg) {
            mantissa = mantissa.substring(1);
        }
        // 规格化：单一非零整数位
        String digits = mantissa.replace(".", "");
        int adjusted = exp + (mantissa.indexOf('.') - 1);
        StringBuilder sb = new StringBuilder();
        if (digits.length() == 1) {
            sb.append(digits);
        } else {
            String frac = digits.substring(1);
            // 剥离最短表示的尾零（1.0E21 → "1e+21"，非 "1.0e+21"）
            while (frac.endsWith("0")) {
                frac = frac.substring(0, frac.length() - 1);
            }
            sb.append(digits.charAt(0));
            if (!frac.isEmpty()) {
                sb.append('.').append(frac);
            }
        }
        String expStr = Math.abs(adjusted) < 10 ? "0" + Math.abs(adjusted) : String.valueOf(Math.abs(adjusted));
        sb.append('e').append(adjusted < 0 ? '-' : '+').append(expStr);
        return (neg ? "-" : "") + sb;
    }

    private static int decimalExponent(String shortest, double v) {
        int e = shortest.indexOf('E');
        String mantissa = e < 0 ? shortest : shortest.substring(0, e);
        int exp = e < 0 ? 0 : Integer.parseInt(shortest.substring(e + 1));
        int point = mantissa.indexOf('.');
        return exp + (point < 0 ? mantissa.length() - 1 : point - 1);
    }

    /**
     * 对应 Go {@code strconv.ParseFloat}（十进制 + 十六进制浮点 + 下划线分隔）。
     * 词法器已保证词法合法，这里的解析失败对应 Go “error parsing number”。
     */
    public static double parseFloat(String s) {
        String t = s.replace("_", "");
        if (t.isEmpty()) {
            return Double.NaN;
        }
        if (t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("+inf") || t.equalsIgnoreCase("infinity") || t.equalsIgnoreCase("+infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (t.equalsIgnoreCase("-inf") || t.equalsIgnoreCase("-infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        if (t.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x") || lower.startsWith("-0x") || lower.startsWith("+0x")) {
            return parseHexFloat(t);
        }
        return Double.parseDouble(t);
    }

    /**
     * 十六进制浮点（0x1FFFP-16 风格），Java 标准库不支持，手工解析。
     */
    private static double parseHexFloat(String t) {
        boolean neg = t.startsWith("-");
        String hex = neg ? t.substring(1) : t;
        if (hex.startsWith("+")) {
            hex = hex.substring(1);
        }
        hex = hex.substring(2); // 去掉 0x
        int p = hex.indexOf('p');
        if (p < 0) {
            // 无指数：按十六进制整数处理
            if (hex.indexOf('.') >= 0) {
                throw new NumberFormatException("hex float without exponent: " + t);
            }
            double v = Long.parseLong(hex, 16);
            return neg ? -v : v;
        }
        String mantissa = hex.substring(0, p);
        int exp = Integer.parseInt(hex.substring(p + 1));
        int dot = mantissa.indexOf('.');
        String intPart = dot < 0 ? mantissa : mantissa.substring(0, dot);
        String fracPart = dot < 0 ? "" : mantissa.substring(dot + 1);
        double value = 0;
        for (int i = 0; i < intPart.length(); i++) {
            value = value * 16 + Character.digit(intPart.charAt(i), 16);
        }
        double scale = 1.0 / 16.0;
        for (int i = 0; i < fracPart.length(); i++) {
            value += Character.digit(fracPart.charAt(i), 16) * scale;
            scale /= 16;
        }
        value *= Math.pow(2, exp);
        return neg ? -value : value;
    }
}

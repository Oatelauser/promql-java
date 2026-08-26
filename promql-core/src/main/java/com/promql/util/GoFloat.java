package com.promql.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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
     * 最短往返十进制表示：优先走 {@link Double#toString} 快速路径（JDK 19+
     * 即 Ryū 最短表示，与 Go ftoa 同一「最短位数 + 距 v 最近 + 平局偶舍入」
     * 规则），校验不过再退回逐档收紧（{@code BigDecimal} 四舍五入到 p 位），
     * 取首个数值回 {@code v} 的形式，并统一输出 {@code d[.ddd]E±x} 指数形态
     * （消费方的解析域；BigDecimal.toString 会在部分区间输出 "0.00001"
     * 这类无指数形态）。<b>不能无条件信任 {@code Double.toString}</b>——
     * JDK 17 运行时是旧 FloatingDecimal，偶发比最短多一位（如次正规
     * Java "4.9E-324" vs Go "5E-324"，B11 黄金向量抓出），因此快速路径
     * 需两重校验：候选精确回读 {@code v}，且 {@code k-1} 位收紧不可区分
     * （不可区分 = 不是最短 → 退回循环，由 BigDecimal 逐档裁判）。
     * 语义等价于 Go 最短选择：最接近 v 的 p 位十进制若落在往返区间内，
     * 即 Go 在该位数上的答案。
     */
    private static String shortest(double v) {
        if (v == 0.0) {
            return (Double.doubleToRawLongBits(v) < 0) ? "-0" : "0";
        }
        // 快速路径：一次 toString + 一次回读 + 一次 BigDecimal 最短性校验，
        // 命中时免去 1..16 档逐档 BigDecimal 构造（打印热路径的主体开销）。
        String java = Double.toString(v);
        String cand = javaToExpString(java);
        if (cand != null) {
            int e = cand.indexOf('E');
            String digitsPart = cand.startsWith("-") ? cand.substring(1, e) : cand.substring(0, e);
            int k = digitsPart.length() - (digitsPart.indexOf('.') < 0 ? 0 : 1);
            if (parsesTo(java, v) && (k == 1 || !distinguishableAt(v, k - 1))) {
                return cand;
            }
        }
        for (int p = 1; p < 17; p++) {
            BigDecimal c = new BigDecimal(v, new MathContext(p, RoundingMode.HALF_EVEN))
                    .stripTrailingZeros();
            if (c.doubleValue() == v) {
                return toExpString(c);
            }
        }
        return Double.toString(v);
    }

    /** {@code s} 精确解析回 {@code v}（NaN 不相等，Inf/有限值按 ==）。 */
    private static boolean parsesTo(String s, double v) {
        try {
            return Double.parseDouble(s) == v;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** p 位收紧后是否仍能区分出 {@code v}（与逐档循环同一裁判谓词）。 */
    private static boolean distinguishableAt(double v, int p) {
        return new BigDecimal(v, new MathContext(p, RoundingMode.HALF_EVEN))
                .stripTrailingZeros().doubleValue() == v;
    }

    /**
     * {@link Double#toString} 的输出（{@code [-]d[.ddd][E±x]} 或定点形态）
     * → 本类规范指数形态；非数字形态（NaN/Infinity 等未在入口拦截的）
     * 返回 {@code null} 由调用方退回慢路径。尾零剥离与 {@link #toExpString}
     * 一致（{@code "1.0E23"} → {@code "1E23"}）。
     */
    private static String javaToExpString(String s) {
        int e = s.indexOf('E');
        String mantissa = e < 0 ? s : s.substring(0, e);
        int exp = e < 0 ? 0 : Integer.parseInt(s.substring(e + 1));
        boolean neg = mantissa.startsWith("-");
        if (neg) {
            mantissa = mantissa.substring(1);
        }
        int dot = mantissa.indexOf('.');
        String intPart = dot < 0 ? mantissa : mantissa.substring(0, dot);
        String frac = dot < 0 ? "" : mantissa.substring(dot + 1);
        String digits = intPart + frac;
        int lead = 0;
        while (lead < digits.length() && digits.charAt(lead) == '0') {
            lead++;
        }
        if (lead == digits.length() || !allAsciiDigits(digits)) {
            return null;
        }
        digits = digits.substring(lead);
        while (digits.length() > 1 && digits.charAt(digits.length() - 1) == '0') {
            digits = digits.substring(0, digits.length() - 1);
        }
        // 首位有效位相对原小数点：整数部分长度 − 前导零数 − 1（+ 指数）
        int adjusted = intPart.length() - lead - 1 + exp;
        String d = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (neg ? "-" : "") + d + "E" + adjusted;
    }

    private static boolean allAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** BigDecimal → 规范指数形态（首位非零，负指数也用 E）。 */
    private static String toExpString(BigDecimal bd) {
        String digits = bd.unscaledValue().abs().toString();
        int adjusted = bd.precision() - bd.scale() - 1;
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (bd.signum() < 0 ? "-" : "") + mantissa + "E" + adjusted;
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
        // 无小数点形态（如 shortest 的 "1E+6"）时按整串长度定位小数点
        int dot = mantissa.indexOf('.');
        int adjusted = exp + (dot < 0 ? mantissa.length() - 1 : dot - 1);
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
        // 剥前导负号：否则 '.' 下标右移一位，负数十进制指数多算 1
        // （如 -123456.789 误判 exp=6 走科学分支；B11 黄金向量抓出）
        if (mantissa.startsWith("-")) {
            mantissa = mantissa.substring(1);
        }
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
        try {
            if (lower.startsWith("0x") || lower.startsWith("-0x") || lower.startsWith("+0x")) {
                return parseHexFloat(t);
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            // Go 抛 *strconv.NumError：strconv.ParseFloat: parsing "<s>": invalid syntax。
            // 消息引原始输入 s（保留下划线，与 Go 一致）；数值范围错误保留 range 后缀。
            String m = e.getMessage();
            String suffix = m != null && m.contains("value out of range") ? "value out of range" : "invalid syntax";
            throw new NumberFormatException(
                    "strconv.ParseFloat: parsing " + GoStrings.quote(s) + ": " + suffix);
        }
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

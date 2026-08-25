package com.promql.util;

import java.util.Map;

/**
 * Prometheus {@code model.Duration} 的格式化与解析，对应 Go
 * {@code github.com/prometheus/common/model/time.go} 的
 * {@code Duration.String()} 与 {@code ParseDuration}。
 *
 * <p>快照未 vendored 该包，本类按上游权威实现逐行移植（上游源码已核对）。
 * 供打印器（区间/offset 时长、时长字面量）与解析器
 * （parse.go 的 {@code parseDuration}）共用。时长一律以纳秒计（Q9）。
 *
 * <p>语义要点：
 * <ul>
 *   <li>格式化：毫秒级分解，单位从大到小 y/w/d/h/m/s/ms；y 与 w 仅当
 *       余数整除时输出（90d 不写作 12w6d）；0 → {@code "0s"}；负数带
 *       {@code -} 前缀（仅格式化路径；解析不接受负数）。</li>
 *   <li>解析：单位必须严格从大到小出现（1m1d 非法）；支持无单位的
 *       {@code "0"}；单位未知/顺序错误 → Go 原文错误消息；
 *       溢出（约 290 年以上）→ {@code "duration out of range"}。</li>
 * </ul>
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    /**
     * 单 unit：名称 → （序位，纳秒倍率）。序位仅用于“从大到小”顺序校验。
     */
    private record Unit(int pos, long mult) {
    }

    private static final Map<String, Unit> UNIT_MAP = Map.of(
            "ms", new Unit(7, 1_000_000L),
            "s", new Unit(6, 1_000_000_000L),
            "m", new Unit(5, 60_000_000_000L),
            "h", new Unit(4, 3_600_000_000_000L),
            "d", new Unit(3, 86_400_000_000_000L),
            "w", new Unit(2, 604_800_000_000_000L),
            "y", new Unit(1, 31_536_000_000_000_000L));

    /**
     * 对应 Go {@code (model.Duration).String()}：纳秒 → 最短时长串。
     *
     * @param nanos 时长（纳秒），可为负（打印器对负 offset 自带 {@code -}，
     *              但本方法同样支持负值）
     */
    public static String format(long nanos) {
        long ms = nanos / 1_000_000L;
        if (ms == 0) {
            return "0s";
        }
        String sign = "";
        if (ms < 0) {
            sign = "-";
            ms = -ms;
        }
        StringBuilder r = new StringBuilder();
        ms = appendUnit(r, ms, "y", 1000L * 60 * 60 * 24 * 365, true);
        ms = appendUnit(r, ms, "w", 1000L * 60 * 60 * 24 * 7, true);
        ms = appendUnit(r, ms, "d", 1000L * 60 * 60 * 24, false);
        ms = appendUnit(r, ms, "h", 1000L * 60 * 60, false);
        ms = appendUnit(r, ms, "m", 1000L * 60, false);
        ms = appendUnit(r, ms, "s", 1000L, false);
        appendUnit(r, ms, "ms", 1L, false);
        return sign + r;
    }

    /**
     * Go String() 内的闭包 {@code f(unit, mult, exact)}；返回剩余毫秒。
     */
    private static long appendUnit(StringBuilder r, long ms, String unit, long mult, boolean exact) {
        if (exact && ms % mult != 0) {
            return ms;
        }
        long v = ms / mult;
        if (v > 0) {
            r.append(v).append(unit);
            ms -= v * mult;
        }
        return ms;
    }

    /**
     * 对应 Go {@code model.ParseDuration(s)}（不支持负数）。错误消息与 Go
     * 逐字一致（含 {@code %q} 引号形式），供解析器透传给 {@code yylex.Error}。
     *
     * @return 时长（纳秒）
     * @throws IllegalArgumentException Go 各错误分支（消息为英文原文）
     */
    public static long parse(String s) {
        if ("0".equals(s)) {
            // 允许无单位的 0。
            return 0L;
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty duration string");
        }
        String orig = s;
        long dur = 0L;
        int lastUnitPos = 0;

        while (!s.isEmpty()) {
            char c0 = s.charAt(0);
            if (c0 < '0' || c0 > '9') {
                throw invalid(orig);
            }
            int i = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            long v;
            try {
                v = Long.parseLong(s.substring(0, i));
            } catch (NumberFormatException e) {
                throw invalid(orig);
            }
            s = s.substring(i);

            // 消费单位（任意非数字字符）。
            i = 0;
            while (i < s.length() && (s.charAt(i) < '0' || s.charAt(i) > '9')) {
                i++;
            }
            if (i == 0) {
                throw invalid(orig);
            }
            String u = s.substring(0, i);
            s = s.substring(i);
            Unit unit = UNIT_MAP.get(u);
            if (unit == null) {
                throw new IllegalArgumentException(String.format(
                        "unknown unit %s in duration %s", GoStrings.quote(u), GoStrings.quote(orig)));
            }
            if (unit.pos() <= lastUnitPos) {
                // 单位必须从大到小。
                throw invalid(orig);
            }
            lastUnitPos = unit.pos();
            // 溢出检查：v > 2^63/mult（Go 用 uint64；各倍率均含因子 5，
            // floor(2^63/mult) == floor((2^63-1)/mult)，Java long 除法同值）。
            if (v > Long.MAX_VALUE / unit.mult()) {
                throw new IllegalArgumentException("duration out of range");
            }
            long term = v * unit.mult();
            long sum = dur + term;
            if (sum < 0) {
                // 正数加正数回绕 ⟺ 越过 2^63-1（Go: dur > 1<<63-1）。
                throw new IllegalArgumentException("duration out of range");
            }
            dur = sum;
        }
        return dur;
    }

    private static IllegalArgumentException invalid(String orig) {
        return new IllegalArgumentException(String.format(
                "not a valid duration string: %s", GoStrings.quote(orig)));
    }
}

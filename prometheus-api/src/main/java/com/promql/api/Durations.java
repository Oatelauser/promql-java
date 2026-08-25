package com.promql.api;

import java.time.Duration;

/**
 * Prometheus 时长字面量（{@code "30s"} / {@code "1500ms"}）的格式化与解析
 * （内部工具）。仅支持本客户端产出的两种单位，不实现完整 PromQL 时长文法。
 */
final class Durations {

    private Durations() {
    }

    /** 整秒 → 秒字面量；否则用毫秒字面量（Prometheus 两者均接受）。 */
    static String format(Duration d) {
        long ms = d.toMillis();
        if (ms % 1000 == 0) {
            return (ms / 1000) + "s";
        }
        return ms + "ms";
    }

    /** 解析 {@link #format} 产出的字面量；不认识返回 {@code null}。 */
    static Duration parse(String s) {
        try {
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            }
        } catch (NumberFormatException ignored) {
            // 返回 null 由调用方决定默认行为
        }
        return null;
    }
}

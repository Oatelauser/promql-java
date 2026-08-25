package com.promql.labels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 标签集合的工具方法，服务 {@code ParseMetric} 路径。
 *
 * <p>对应 Go：{@code model/labels} 包的 {@code New} 与
 * {@code NewBuilder(...).Set(MetricName, ...).Labels()}（快照未 vendored
 * 该包，语义按上游标准行为移植：按 name 稳定排序；Builder.Set 语义为
 * 同名标签 last-wins）。
 */
public final class Labels {

    /**
     * 指标名标签，对应 Go {@code labels.MetricName}。
     */
    public static final String METRIC_NAME = "__name__";

    private Labels() {
    }

    /**
     * 构造排序后的标签列表，对应 Go {@code labels.New(ls...)}：
     * 按 name 稳定排序（同名保持原相对顺序），不做去重。
     */
    public static List<Label> newLabels(List<Label> ls) {
        List<Label> res = new ArrayList<>(ls);
        res.sort(Comparator.comparing(Label::name));
        return res;
    }

    /**
     * 对应 Go 文法动作
     * {@code labels.NewBuilder($2).Set(labels.MetricName, $1.Val).Labels()}：
     * 移除已有的 {@code __name__} 后写入指标名，再按 name 稳定排序。
     */
    public static List<Label> withMetricName(List<Label> ls, String metricName) {
        List<Label> res = new ArrayList<>(ls.size() + 1);
        for (Label l : ls) {
            if (!METRIC_NAME.equals(l.name())) {
                res.add(l);
            }
        }
        res.add(new Label(METRIC_NAME, metricName));
        res.sort(Comparator.comparing(Label::name));
        return res;
    }

    /**
     * 标签名是否合法，对应 Go {@code model.UTF8Validation.IsValidLabelName}。
     *
     * <p>快照未 vendored 该实现，按上游语义移植：非空、全部可打印，且不含
     * {@code = { } ,} 与引号等会破坏 selector 语法的字符（UTF-8 标签名方案）。
     */
    public static boolean isValidLabelName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '=' || c == '{' || c == '}' || c == ',' || c == '"' || c == '\'') {
                return false;
            }
            if (Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Legacy 校验方案，对应 Go {@code model.LegacyValidation.IsValidLabelName}：
     * {@code [a-zA-Z_][a-zA-Z0-9_]*}。
     *
     * <p>仅用于<b>打印侧</b>（Go printer.go 的 writeLabels 用它决定是否给
     * grouping/匹配标签加引号，保证输出可回读）；解析侧校验仍用
     * {@link #isValidLabelName(String)}（UTF-8 方案）。
     */
    public static boolean isValidLegacyLabelName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                    || (i > 0 && c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}

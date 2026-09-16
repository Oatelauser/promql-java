package io.github.oatelauser.promql.functions;

import io.github.oatelauser.promql.value.ValueType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.oatelauser.promql.value.ValueType.MATRIX;
import static io.github.oatelauser.promql.value.ValueType.SCALAR;
import static io.github.oatelauser.promql.value.ValueType.STRING;
import static io.github.oatelauser.promql.value.ValueType.VECTOR;

/**
 * PromQL 全部函数的静态注册表，对应 Go {@code parser/functions.go} 的
 * {@code Functions} map 与 {@code getFunction}。
 *
 * <p>共 90 个函数（其中 17 个 experimental，受
 * {@code EnableExperimentalFunctions} 门控）。条目顺序与 Go 源文件一致，
 * 便于对照快照 diff。
 */
public final class Functions {

    private static final Map<String, Function> FUNCTIONS = build();

    private Functions() {
    }

    private static Map<String, Function> build() {
        Map<String, Function> m = new LinkedHashMap<>();
        reg(m, "abs", 0, VECTOR, false, VECTOR);
        reg(m, "absent", 0, VECTOR, false, VECTOR);
        reg(m, "absent_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "acos", 0, VECTOR, false, VECTOR);
        reg(m, "acosh", 0, VECTOR, false, VECTOR);
        reg(m, "asin", 0, VECTOR, false, VECTOR);
        reg(m, "asinh", 0, VECTOR, false, VECTOR);
        reg(m, "atan", 0, VECTOR, false, VECTOR);
        reg(m, "atanh", 0, VECTOR, false, VECTOR);
        reg(m, "avg_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "ceil", 0, VECTOR, false, VECTOR);
        reg(m, "changes", 0, VECTOR, false, MATRIX);
        reg(m, "clamp", 0, VECTOR, false, VECTOR, SCALAR, SCALAR);
        reg(m, "clamp_max", 0, VECTOR, false, VECTOR, SCALAR);
        reg(m, "clamp_min", 0, VECTOR, false, VECTOR, SCALAR);
        reg(m, "cos", 0, VECTOR, false, VECTOR);
        reg(m, "cosh", 0, VECTOR, false, VECTOR);
        reg(m, "count_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "days_in_month", 1, VECTOR, false, VECTOR);
        reg(m, "day_of_month", 1, VECTOR, false, VECTOR);
        reg(m, "day_of_week", 1, VECTOR, false, VECTOR);
        reg(m, "day_of_year", 1, VECTOR, false, VECTOR);
        reg(m, "deg", 0, VECTOR, false, VECTOR);
        reg(m, "end", 0, SCALAR, true);
        reg(m, "delta", 0, VECTOR, false, MATRIX);
        reg(m, "deriv", 0, VECTOR, false, MATRIX);
        reg(m, "exp", 0, VECTOR, false, VECTOR);
        reg(m, "first_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "floor", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_avg", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_count", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_sum", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_stddev", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_stdvar", 0, VECTOR, false, VECTOR);
        reg(m, "histogram_fraction", 0, VECTOR, false, SCALAR, SCALAR, VECTOR);
        reg(m, "histogram_quantile", 0, VECTOR, false, SCALAR, VECTOR);
        reg(m, "histogram_quantiles", 9, VECTOR, true, VECTOR, STRING, SCALAR, SCALAR);
        reg(m, "double_exponential_smoothing", 0, VECTOR, true, MATRIX, SCALAR, SCALAR);
        reg(m, "hour", 1, VECTOR, false, VECTOR);
        reg(m, "idelta", 0, VECTOR, false, MATRIX);
        reg(m, "increase", 0, VECTOR, false, MATRIX);
        reg(m, "info", 1, VECTOR, true, VECTOR, VECTOR);
        reg(m, "irate", 0, VECTOR, false, MATRIX);
        reg(m, "label_replace", 0, VECTOR, false, VECTOR, STRING, STRING, STRING, STRING);
        reg(m, "label_join", -1, VECTOR, false, VECTOR, STRING, STRING, STRING);
        reg(m, "max_of", 0, SCALAR, true, SCALAR, SCALAR);
        reg(m, "last_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "min_of", 0, SCALAR, true, SCALAR, SCALAR);
        reg(m, "ln", 0, VECTOR, false, VECTOR);
        reg(m, "log10", 0, VECTOR, false, VECTOR);
        reg(m, "log2", 0, VECTOR, false, VECTOR);
        reg(m, "mad_over_time", 0, VECTOR, true, MATRIX);
        reg(m, "max_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "min_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "ts_of_first_over_time", 0, VECTOR, true, MATRIX);
        reg(m, "ts_of_max_over_time", 0, VECTOR, true, MATRIX);
        reg(m, "ts_of_min_over_time", 0, VECTOR, true, MATRIX);
        reg(m, "ts_of_last_over_time", 0, VECTOR, true, MATRIX);
        reg(m, "minute", 1, VECTOR, false, VECTOR);
        reg(m, "month", 1, VECTOR, false, VECTOR);
        reg(m, "pi", 0, SCALAR, false);
        reg(m, "predict_linear", 0, VECTOR, false, MATRIX, SCALAR);
        reg(m, "present_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "quantile_over_time", 0, VECTOR, false, SCALAR, MATRIX);
        reg(m, "rad", 0, VECTOR, false, VECTOR);
        reg(m, "range", 0, SCALAR, true);
        reg(m, "rate", 0, VECTOR, false, MATRIX);
        reg(m, "resets", 0, VECTOR, false, MATRIX);
        reg(m, "round", 1, VECTOR, false, VECTOR, SCALAR);
        reg(m, "scalar", 0, SCALAR, false, VECTOR);
        reg(m, "sgn", 0, VECTOR, false, VECTOR);
        reg(m, "sin", 0, VECTOR, false, VECTOR);
        reg(m, "sinh", 0, VECTOR, false, VECTOR);
        reg(m, "sort", 0, VECTOR, false, VECTOR);
        reg(m, "sort_desc", 0, VECTOR, false, VECTOR);
        reg(m, "sort_by_label", -1, VECTOR, true, VECTOR, STRING);
        reg(m, "sort_by_label_desc", -1, VECTOR, true, VECTOR, STRING);
        reg(m, "sqrt", 0, VECTOR, false, VECTOR);
        reg(m, "start", 0, SCALAR, true);
        reg(m, "start_timestamp", 0, VECTOR, true, VECTOR);
        reg(m, "step", 0, SCALAR, true);
        reg(m, "stddev_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "stdvar_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "sum_over_time", 0, VECTOR, false, MATRIX);
        reg(m, "tan", 0, VECTOR, false, VECTOR);
        reg(m, "tanh", 0, VECTOR, false, VECTOR);
        reg(m, "time", 0, SCALAR, false);
        reg(m, "timestamp", 0, VECTOR, false, VECTOR);
        reg(m, "vector", 0, VECTOR, false, SCALAR);
        reg(m, "year", 1, VECTOR, false, VECTOR);
        return Collections.unmodifiableMap(m);
    }

    /**
     * 注册一个函数（参数顺序：名称、variadic、返回类型、experimental、各参数类型）。
     */
    private static void reg(Map<String, Function> m, String name, int variadic,
            ValueType returnType, boolean experimental, ValueType... argTypes) {
        m.put(name, new Function(name, List.of(argTypes), variadic, returnType, experimental));
    }

    /**
     * 对应 Go {@code getFunction(name, functions)}。
     */
    public static Function getFunction(String name) {
        return FUNCTIONS.get(name);
    }

    /**
     * 全部注册函数（只读视图）。
     */
    public static Map<String, Function> all() {
        return FUNCTIONS;
    }
}

package io.github.oatelauser.promql.functions;

import io.github.oatelauser.promql.value.ValueType;

import java.util.List;
import java.util.Objects;

/**
 * PromQL 函数的静态描述，对应 Go {@code parser.Function}。
 *
 * <p>字段语义与 Go 一致：
 * <ul>
 *   <li>{@code argTypes}：各参数期望的值类型；</li>
 *   <li>{@code variadic}：0 = 固定参数个数；正数 n = 末尾同类型参数可重复
 *       最多 n 次；负数 -n = 末尾最多 n 个可选参数（如 {@code info} 的 1、
 *       {@code sort_by_label} 的 -1）；</li>
 *   <li>{@code experimental}：受 EnableExperimentalFunctions 门控。</li>
 * </ul>
 */
public record Function(
        String name,
        List<ValueType> argTypes,
        int variadic,
        ValueType returnType,
        boolean experimental) {

    public Function {
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(argTypes, "argTypes 不能为空");
        Objects.requireNonNull(returnType, "returnType 不能为空");
        argTypes = List.copyOf(argTypes);
    }
}

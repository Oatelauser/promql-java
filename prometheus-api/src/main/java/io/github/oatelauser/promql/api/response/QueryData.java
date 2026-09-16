package io.github.oatelauser.promql.api.response;

import java.util.List;

/**
 * 查询结果数据（{@code /api/v1/query}、{@code /api/v1/query_range} 的
 * {@code data} 部分），四种 {@code resultType} 一一对应四个变体。
 *
 * <p>对应 Go：{@code promql.Value} 的四个实现（Vector/Matrix/Scalar/String）。
 * 变体由 {@link io.github.oatelauser.promql.ast.Expr#type()} 静态推断，并在绑定层与服务器
 * {@code resultType} 交叉校验（Q4=C 双保险）。
 *
 * <p>调用方 pattern match：
 * <pre>{@code
 * switch (client.query(expr)) {
 *     case VectorData(List<VectorSample> samples, List<String> w) -> ...;
 *     case MatrixData(List<MatrixSeries> series, List<String> w) -> ...;
 *     case ScalarData(long ts, SampleValue v, List<String> w) -> ...;
 *     case StringData(long ts, String v, List<String> w) -> ...;
 * }
 * }</pre>
 */
public sealed interface QueryData permits VectorData, MatrixData, ScalarData, StringData {

    /** 响应 envelope 携带的 {@code warnings} 列表（无则空）。 */
    List<String> warnings();
}

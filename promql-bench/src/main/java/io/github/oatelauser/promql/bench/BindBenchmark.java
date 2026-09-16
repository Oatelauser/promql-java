package io.github.oatelauser.promql.bench;

import io.github.oatelauser.promql.api.response.ResponseBinder;
import io.github.oatelauser.promql.value.ValueType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * B12：prometheus-api 响应绑定微基准（Gson 树 → record）。
 * matrix：500 序列 × 120 样本；vector：1000 瞬时样本。形态合成自官方
 * HTTP API 文档示例。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class BindBenchmark {

    String matrixJson;
    String vectorJson;

    @Setup
    public void setup() {
        StringBuilder matrix = new StringBuilder(1 << 20)
                .append("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                matrix.append(',');
            }
            matrix.append("{\"metric\":{\"__name__\":\"up\",\"instance\":\"i").append(i)
                    .append("\",\"job\":\"j\"},\"values\":[");
            for (int k = 0; k < 120; k++) {
                if (k > 0) {
                    matrix.append(',');
                }
                matrix.append('[').append(1435341450 + k * 15).append(",\"").append(k % 2).append("\"]");
            }
            matrix.append("]}");
        }
        matrixJson = matrix.append("]}}").toString();

        StringBuilder vector = new StringBuilder(1 << 16)
                .append("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append("{\"metric\":{\"__name__\":\"up\",\"instance\":\"i").append(i)
                    .append("\"},\"value\":[1435341451.781,\"").append(i % 100).append("\"]}");
        }
        vectorJson = vector.append("]}}").toString();
    }

    @Benchmark
    public Object bindMatrix() {
        return ResponseBinder.bindQuery(matrixJson, 200, ValueType.MATRIX);
    }

    @Benchmark
    public Object bindVector() {
        return ResponseBinder.bindQuery(vectorJson, 200, ValueType.VECTOR);
    }
}

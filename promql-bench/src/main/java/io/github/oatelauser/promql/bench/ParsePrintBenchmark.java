package io.github.oatelauser.promql.bench;

import io.github.oatelauser.promql.Promql;
import io.github.oatelauser.promql.ast.Expr;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * B12：promql-core 解析/打印微基准。
 *
 * <p>运行：{@code mvn -pl promql-bench -am package && java -jar promql-bench/target/bench.jar}
 * （可加参数过滤，如 {@code ParsePrintBenchmark.print}、{@code -p query=up}、{@code -f 2}）。
 *
 * <p>query 维度从简到繁：裸选择器 → 带过滤 → 真实聚合 → 直方图分位数 →
 * 32 层括号嵌套（栈深上界参考）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class ParsePrintBenchmark {

    @Param({
            "up",
            "up{job=\"prometheus\",instance=\"localhost:9090\"}",
            "sum(rate(http_requests_total{job=\"api\"}[5m])) by (job, instance)",
            "histogram_quantile(0.9, sum by (le) (rate(http_request_duration_seconds_bucket[10m])))",
            "((((((((((((((((((((((((((((((up))))))))))))))))))))))))))))))))",
    })
    String query;

    Expr ast;

    @Setup
    public void setup() {
        ast = Promql.parse(query);
    }

    @Benchmark
    public Expr parse() {
        return Promql.parse(query);
    }

    @Benchmark
    public Expr tryParse() {
        return Promql.tryParse(query);
    }

    @Benchmark
    public String print() {
        return Promql.print(ast);
    }
}

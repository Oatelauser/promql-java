package io.github.oatelauser.promql.bench;

import io.github.oatelauser.promql.util.GoFloat;
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
 * B12：GoFloat 浮点格式化/解析微基准（打印热路径：NumberLiteral、fill 值、
 * `{@code @}` 修饰符时间戳；解析热路径：HTTP 响应样本值绑定）。
 *
 * <p>运行：{@code java -jar promql-bench/target/bench.jar FormatBenchmark}
 * （可 {@code -p value=1435341451.781} 过滤单值）。
 *
 * <p>value 维度覆盖典型形态：整数/常规小数（快速路径直通）、定点↔科学
 * 阈值边界（999999 / 1000001 / 0.0001 / 1e-7）、时间戳尺度、次正规
 * （5e-324，快速路径守卫退回逐档裁判的形态）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class FormatBenchmark {

    @Param({
            "1",
            "0.1",
            "123456.789",
            "1435341451.781",
            "999999",
            "1000001",
            "0.0001",
            "1e-7",
            "5e-324",
    })
    String value;

    double v;
    String printed;

    @Setup
    public void setup() {
        v = Double.parseDouble(value);
        printed = GoFloat.formatFloatG(v);
    }

    @Benchmark
    public String formatFloatF() {
        return GoFloat.formatFloatF(v);
    }

    @Benchmark
    public String formatFloatG() {
        return GoFloat.formatFloatG(v);
    }

    @Benchmark
    public double parseFloat() {
        return GoFloat.parseFloat(printed);
    }
}

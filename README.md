# promql-java

PromQL 全栈 Java 工具链（Java 17），多模块 Maven 项目：

- **promql-core** — PromQL 字符串 ⇄ AST 语法库，**零运行时依赖**。移植自
  Prometheus 官方 Go 实现的语法层（参考快照 `docs/promql/`），一致性以官方
  `parse_test.go` 全表背书。
- **prometheus-api** — 对接 Prometheus HTTP API 的客户端。基于 PromQL AST
  **静态推断响应类型**，完整覆盖查询族所有结果对象类型；默认传输为 JDK 自带
  `java.net.http.HttpClient`（可子类化替换），仅额外依赖 Gson（树解析）。
- **promql-bench** — JMH 基准（解析/打印/响应绑定），手动运行、不进 CI。

```java
// ── promql-core：解析/打印 ──────────────────────────────
Expr ast = Promql.parse("sum(rate(foo[5m])) by (job)");
String query = Promql.print(ast);      // "sum by (job) (rate(foo[5m]))"
Expr maybe = Promql.tryParse("foo{a=");  // null（不抛异常形态）

// ── prometheus-api：AST 决定响应类型 ────────────────────
JdkHttpPrometheusClient client = new JdkHttpPrometheusClient("http://localhost:9090");
// expr.type()==VECTOR → 返回 VectorData；与服务器 resultType 交叉校验
VectorData v = client.query(Promql.parse("up"), null, null, VectorData.class);
for (VectorSample s : v.samples()) {
    System.out.println(s.metric() + " = " + s.value() + " @ " + s.toInstant());
}

// 泛型重载类型不符就地失败（不发请求）
// client.query(Promql.parse("1"), null, null, VectorData.class); // IllegalArgumentException

MatrixData m = client.queryRange(Promql.parse("rate(foo[5m])"),
        1435341450000L, 1435341750000L, Duration.ofSeconds(15), null);
// series：每组标签集独立成组（序列边界不丢）；信封 warnings/infos 一并透出
SeriesData s = client.series(null, null, List.of("up", "process_start_time_seconds{job=\"prometheus\"}"));
for (List<Label> labels : s.series()) {
    System.out.println(labels);
}
LabelValuesData jobs = client.labelValues("job", null, null, null);
ExemplarsData exs = client.queryExemplars("http_requests_total", null, null);
```

## 特性

### promql-core

- **全量语法**：官方快照的完整 PromQL 文法——选择器/矩阵/子查询/二元与集合
  运算/聚合/函数/`@` 与 `offset`/时长算术表达式/`anchored`/`smoothed`/
  `fill` 修饰符/UTF-8 标签名（含三种引号风格）。
- **P1 往返契约**：`parse(print(ast))` 与 `ast` 语义等价（比对以打印输出为准）；
  不承诺 P2 字节稳定（打印是规范化形式）。见 `docs/adr/0002`。
- **语法纯 AST**（`docs/adr/0003`）：sealed 接口 + 不可变 record，位置字段
  不参与 equals/hashCode；`Visitor`/`Inspector`/`Walk` 遍历设施齐备。
- **Go 对齐的错误信息**：`PromqlParseException`（unchecked）携带
  `List<ParseError>`，首错的位置与消息与 Go 逐字一致。
- **三重 Go oracle 背书**：官方 352 条表 + 10049 条 strconv 黄金向量 +
  4935 条差分模糊语料（突变+文法随机游走+病态探针，ok 行打印逐字相等、
  fail 行首错位置/消息逐字相等），再生成见 `scripts/fuzz-oracle`。
- **零运行时依赖**。

### prometheus-api

- **AST 驱动的类型推断**：`Expr.type()` 静态决定 `QueryData` 变体
  （vector→VectorData / matrix→MatrixData / scalar→ScalarData / string→StringData），
  并与服务器 `resultType` 运行时交叉校验，不一致抛 `PrometheusException`。
- **完整结果对象覆盖**：native histogram（bucket 四元组）、字符串编码浮点
  （`"NaN"/"+Inf"/"-Inf"` 经 GoFloat）、Unicode/转义标签、`warnings`/`infos`
  信封元数据透传（第 1、2 层端点一致）、六类 `errorType` + 未知宽容归 INTERNAL。
- **纯抽象基类 + JDK 默认实现**：`AbstractPrometheusClient` 只有一个抽象方法
  `send(RawRequest)`；`RawRequest`/`RawResponse` 传输无关（GET→查询串，
  POST→form body），换传输零改动。选择器入参（`series`/`query_exemplars`）
  就地经解析器校验，非法不发请求。
- **异常分层**：业务错误（error envelope/类型不一致/畸形响应）→非检
  `PrometheusException`（携带 errorType/httpStatus/rawBody）；传输失败（IO
  与中断）统一 `UncheckedIOException`，中断先恢复标志位、cause 为
  `InterruptedIOException`。
- **传输加固**：无 `timeout` 参数时默认 HTTP 超时 2m+5s（镜像服务器默认查询
  超时，可配可关）+ 默认 10s 连接超时；`requestDecorator` 注入鉴权 header。

## 快速上手

要求：JDK 17+（工具链 21+ 时按 release 17 编译）。

```bash
mvn test            # 583 用例：promql-core 527 + prometheus-api 56
mvn test -pl promql-core            # 仅语法库（352 官方表 + 22 AST 抽样
                                    #  + 45 字符串→AST + 92 打印黄金 + 14 AST→字符串
                                    #  + 1 GoFloat 黄金向量〔10049 条 Go oracle〕
                                    #  + 1 差分模糊〔4935 条 Go oracle 全量重放〕）
mvn test -pl prometheus-api -am     # 客户端（27 binder golden + 23 基类矩阵
                                    #  + 6 真传输 HttpServer 集成）
mvn -pl promql-bench -am package && java -jar promql-bench/target/bench.jar
                                    # JMH 基准（手动；例：'ParsePrintBenchmark.parse -p query=up'）
```

Maven 坐标（安装到本地库 `mvn install` 后可用）：

```xml
<!-- 仅语法层 -->
<dependency>
    <groupId>com.promql</groupId>
    <artifactId>promql-core</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- 语法层 + HTTP API 客户端（传递引入 Gson） -->
<dependency>
    <groupId>com.promql</groupId>
    <artifactId>prometheus-api</artifactId>
    <version>0.1.0</version>
</dependency>
```

主要入口：`com.promql.Promql`（解析门面）与
`com.promql.api.JdkHttpPrometheusClient` / `AbstractPrometheusClient`（客户端）。
实验开关见 `ParserOptions`（默认全关）。

## 与 Go 实现的关系

- **上游锚点：Prometheus main@2026-08-25**（v3.14.0 之后的 3.15 开发线）。
  快照与 v3.14.0 tag 的实测差异已列明（19 个移植范围文件中 6 个有差异，
  增量已随快照移植）；漂移检测：`bash scripts/check-snapshot.sh [tag]`。
  详见 PORTING.md §0。
- 移植范围与源文件映射：[PORTING.md](PORTING.md)
- 领域术语表：[CONTEXT.md](CONTEXT.md)；架构决策：`docs/adr/`
- 已知分歧（首错中止、UTF-16 位置口径、深递归栈等 11 条）：PORTING.md §3

## 目录结构

```
promql-core/                    # PromQL 语法库（零依赖）
├── src/main/java/com/promql/
│   ├── Promql.java             # 门面：parse / tryParse / parseMetricSelector / parseMetric / print
│   ├── ast/                    # sealed AST（record + 枚举 + Visitor/Inspector/Walk）
│   ├── parser/                 # Parser（递归下降+优先级爬升）、ParserOptions、异常
│   ├── lexer/                  # 词法状态机（Lexer/Item/ItemType）
│   ├── printer/                # Printer.toPromql（规范化输出）+ Tree（调试树）
│   ├── functions/              # 66 函数表
│   ├── labels/                 # Label/LabelMatcher/MatchType/Labels（上游 model/labels 语义）
│   ├── posrange/               # PositionRange
│   ├── util/                   # GoFloat/GoStrings/DurationFormat（Go 语义模拟）
│   └── value/                  # ValueType
└── src/test/                   # 双向测试：ParserConformanceTest（352 官方表）、
                                # ParserAstConformanceTest（22）、StringToAstTest（45）、
                                # PrinterGoldenTest（92）、AstToStringTest（14）、
                                # GoFloatVectorTest（10049 条 Go oracle 黄金向量）、
                                # ParserFuzzDifferentialTest（4935 条差分模糊全量重放）
                                # + resources/（parse_test_cases.tsv、gofloat_vectors.tsv、
                                #   fuzz_diff_cases.tsv）

prometheus-api/                 # Prometheus HTTP API 客户端（依赖 promql-core + Gson）
├── src/main/java/com/promql/
│   ├── api/                    # AbstractPrometheusQueryClient（父层：传输核心+查询端点）、
│   │                           # AbstractPrometheusClient（子层：+查询族端点）、
│   │                           # JdkHttpPrometheusClient（JDK HttpClient 默认实现）、
│   │                           # RawRequest/RawResponse（传输无关）、
│   │                           # Durations/ApiHttp（内部工具）
│   └── api/response/           # sealed QueryData 四变体（含 warnings/infos）
│                               # + SampleValue（Float/Histogram）+ Histogram/Bucket
│                               # + Exemplar + 第 2 层包装（SeriesData/LabelNamesData/
│                               #   LabelValuesData/ExemplarsData）
│                               # + ErrorType/PrometheusException
│                               # + ResponseBinder（Gson 树 → record 绑定）
└── src/test/                   # ResponseBinderTest（26 golden）、
                                # AbstractPrometheusClientTest（19 基类矩阵）、
                                # JdkHttpPrometheusClientTest（4 真传输集成）

extract_cases.py                # parse_test.go → parse_test_cases.tsv 提取器
scripts/                        # check-snapshot.sh（快照 vs 上游 tag 漂移检测）、
                                # gen_gofloat_vectors.go（GoFloat 黄金向量 Go oracle）、
                                # fuzz-oracle/（差分模糊语料生成器 + probe/tree 排查工具）
promql-bench/                   # JMH 基准（ParsePrintBenchmark / BindBenchmark）
docs/                           # promql 参考快照 + adr/
```

## 许可

Apache License 2.0（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)）。
上游：[Prometheus](https://github.com/prometheus/prometheus)（Apache License 2.0）。

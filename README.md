# promql-java

[![CI](https://github.com/Oatelauser/promql-java/actions/workflows/ci.yml/badge.svg)](https://github.com/Oatelauser/promql-java/actions/workflows/ci.yml)

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

// 按请求切换 GET/POST（查询族端点两法等价，POST 规避 URL 长度限制）
// + 未知参数透传（服务器新参数无需等库升级；null 选项 = 端点缺省方法）
SeriesData big = client.series(null, null, manyMatchers, RequestOptions.post());
VectorData v3 = client.query(Promql.parse("up"), null, null,
        RequestOptions.get(List.of(new RawRequest.Param("x-flag", "1"))));
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
- **最短浮点格式化快速路径**：GoFloat 优先采信 JDK 19+ 的 Ryū 最短表示
  （`Double.toString`），两重校验（精确回读 + 最短性）不过即退回 BigDecimal
  逐档裁判——JDK 17 运行时同样正确；10049 条 Go 黄金向量全量背书，混合
  语料实测约 4×（见 `FormatBenchmark`）。

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
- **GET/POST 按请求切换 + 参数透传**：查询族全部端点（query/query_range/
  series/labels/label values/query_exemplars）支持 `RequestOptions` 显式选
  GET 或 POST（两法协议等价，POST 规避长表达式/多 `match[]` 的 URL 长度
  限制），并可透传任意额外参数（同名键形成多值，未知键原样发送）；
  `null` 选项 = 端点既有缺省方法，行为不变。

## 快速上手

要求：JDK 17+（工具链 21+ 时按 release 17 编译）。

```bash
mvn test            # 593 用例：promql-core 527 + prometheus-api 66
mvn test -pl promql-core            # 仅语法库（352 官方表 + 22 AST 抽样
                                    #  + 45 字符串→AST + 92 打印黄金 + 14 AST→字符串
                                    #  + 1 GoFloat 黄金向量〔10049 条 Go oracle〕
                                    #  + 1 差分模糊〔4935 条 Go oracle 全量重放〕）
mvn test -pl prometheus-api -am     # 客户端（27 binder golden + 33 基类矩阵
                                    #  + 6 真传输 HttpServer 集成）
mvn -pl promql-bench -am package && java -jar promql-bench/target/bench.jar
                                    # JMH 基准（手动；例：'ParsePrintBenchmark.parse -p query=up'）
```

Maven 坐标（Maven Central，发布后免认证可用；发布前可 `mvn install` 装进本地库）：

```xml
<!-- 仅语法层 -->
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>promql-core</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 语法层 + HTTP API 客户端（传递引入 Gson） -->
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>prometheus-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

主要入口：`io.github.oatelauser.promql.Promql`（解析门面）与
`io.github.oatelauser.promql.api.JdkHttpPrometheusClient` / `AbstractPrometheusClient`（客户端）。
实验开关见 `ParserOptions`（默认全关）。

## CI 与发布

- **CI**（push main / PR）：JDK **17 + 21** 双矩阵全量测试。JDK 17 不只是
  最低支持运行时——其 `Double.toString` 仍是旧 FloatingDecimal，GoFloat
  快速路径的最短性守卫在该运行时常态走退回分支，慢路径随之持续回归。
- **发布**：推送 `v*` 标签触发 [release 工作流](.github/workflows/release.yml)
  ——全量测试后将 `promql-core` / `prometheus-api`（jar + sources + javadoc，
  gpg 签名）上传 Maven Central 并建 Release 页，测试不过不上传；Portal 上
  人工确认 Publish 后生效。步骤、一次性准备（Token/GPG/Secrets）与消费方
  接入见 [RELEASE.md](RELEASE.md)。

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
├── src/main/java/io/github/oatelauser/promql/
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
├── src/main/java/io/github/oatelauser/promql/
│   ├── api/                    # AbstractPrometheusQueryClient（父层：传输核心+查询端点）、
│   │                           # AbstractPrometheusClient（子层：+查询族端点）、
│   │                           # JdkHttpPrometheusClient（JDK HttpClient 默认实现）、
│   │                           # RawRequest/RawResponse（传输无关）、
│   │                           # RequestOptions（GET/POST 切换+参数透传）、
│   │                           # Durations/ApiHttp（内部工具）
│   └── api/response/           # sealed QueryData 四变体（含 warnings/infos）
│                               # + SampleValue（Float/Histogram）+ Histogram/Bucket
│                               # + Exemplar + 第 2 层包装（SeriesData/LabelNamesData/
│                               #   LabelValuesData/ExemplarsData）
│                               # + ErrorType/PrometheusException
│                               # + ResponseBinder（Gson 树 → record 绑定）
└── src/test/                   # ResponseBinderTest（27 golden）、
                                # AbstractPrometheusClientTest（33 基类矩阵，含
                                #   RequestOptions 方法切换/参数透传 10 例）、
                                # JdkHttpPrometheusClientTest（6 真传输集成）

extract_cases.py                # parse_test.go → parse_test_cases.tsv 提取器
scripts/                        # check-snapshot.sh（快照 vs 上游 tag 漂移检测）、
                                # gen_gofloat_vectors.go（GoFloat 黄金向量 Go oracle）、
                                # fuzz-oracle/（差分模糊语料生成器 + probe/tree 排查工具）
promql-bench/                   # JMH 基准（ParsePrintBenchmark / BindBenchmark /
                                #   FormatBenchmark〔GoFloat 格式化/解析〕）
docs/                           # promql 参考快照 + adr/
```

## 许可

Apache License 2.0（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)）。
上游：[Prometheus](https://github.com/prometheus/prometheus)（Apache License 2.0）。

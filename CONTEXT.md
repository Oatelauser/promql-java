# promql-java

PromQL Java 工具链的领域语言。两个模块：**promql-core** 把 Prometheus 官方 Go 实现
（docs/promql 参考快照，锚定 **v3.14.0 / 2026-08-17**，取自其后 main 分支）的
语法层移植为 Java——PromQL 字符串 ⇄ AST；**prometheus-api** 在其上对接
Prometheus HTTP API——由 AST 静态推断响应类型并完整绑定所有结果对象。

## Language

### 解析与打印

**Parse（解析）**:
把 PromQL 字符串转换为完全填充的 AST（每个语法字段都有来自源文本的值，不存在空壳节点）。
_Avoid_: deserialize, decode, unmarshal

**Print（打印 / 规范化输出）**:
把 AST 渲染回 PromQL 字符串。输出是**规范化形式**：label matcher 按字母排序、空白标准化、引号与时长格式统一。忠实移植 Go printer 的行为。
_Avoid_: serialize, render, dump

**Re-parse stability（再解析稳定性，P1）**:
`parse(print(ast))` 与 `ast` 语义等价。这是本库承诺的往返契约。
_Avoid_: idempotence（含义更窄）

**Byte stability（字节稳定性，P2）**:
`print(parse(q))` 与 `q` 逐字节相同。**明确不承诺**——AST 不保留原始 token，打印必然规范化。
_Avoid_: round-trip fidelity（歧义：未指明是 P1 还是 P2）

### AST 相关

**AST（抽象语法树）**:
Parse 的产物。纯语法对象：只承载解析器产生的字段，不承载求值期数据。

**Syntax purity（语法纯度）**:
AST 的边界原则：解析器产出的字段进 AST；引擎/存储执行期字段（如 `Series`、`StepInvariantExpr` 包装、`EvalStmt`）不进。将来若做求值引擎，其状态另行存放，不回填 AST。

**Position range（位置区间）**:
AST 节点/错误携带的源文本区间（零索引 start/end）。用于错误定位与工具链，不参与语义相等。

**Feature flag（特性开关）**:
门控实验性语法的开关，逐一镜像 Go `parser.Options` 的四个开关。解析器不得比同配置的 Prometheus 更严格或更宽松。

**Parse error（解析错误）**:
带位置区间的单条失败信息；多条聚合为一个列表（沿用 Go 的累积模型）。
_Avoid_: syntax error（不承载位置语义时禁用）

**Conformance suite（一致性测试集）**:
移植自 Go `parse_test.go` 的金标准用例集（输入 → 期望规范化输出 / 期望错误文本），是"与参考快照行为一致"的最终裁判。打印幂等性测试（P1 契约的可执行形式）附属于它。
_Avoid_: golden files（指别的测试形态时勿混用）

### PromQL 语法词汇（Go 名称 = 唯一权威）

**Vector selector（向量选择器）**:
`http_requests_total{job="api"}` 这类即时向量选择。Go 类型 `VectorSelector`。

**Matrix selector（矩阵/区间选择器）**:
`x[5m]` 区间向量选择。Go 类型名为 `MatrixSelector`，PromQL 文档口语称 "range selector"——代码中一律用 Matrix selector。
_Avoid_: range selector（代码中）

**Subquery（子查询）**:
`x[5m:1m]` 形式的嵌套查询。Go 类型 `SubqueryExpr`。

**Duration expression（时长表达式）**:
`[26m+4m]` 括号内的时长算术。实验特性，受 `ExperimentalDurationExpr` 门控。
_Avoid_: duration literal（指 `30m` 单个字面量时用这个，勿混）

**Label matcher（标签匹配器）**:
`{job=~"a.+"}` 中单个 name/op/value 三元组。库内自带轻量值类型，v1 只做数据、不做匹配求值。

### Prometheus HTTP API 客户端（prometheus-api）

**Layer 1 / Layer 2（第 1 层 / 第 2 层端点）**:
官方 HTTP API 的覆盖分层。第 1 层 = 查询族（`/query`、`/query_range`，4 种 resultType）；
第 2 层 = 查询族的元数据端点（`/series`、`/labels`、`/label/<name>/values`、
`/query_exemplars`）。管理端点（targets/rules/alerts 等，第 3 层）明确不在范围。

**Result type（resultType）**:
服务器在 `data.resultType` 声明的响应形状：`vector`/`matrix`/`scalar`/`string`
四种，与 AST 的 `ValueType` 一一对应。客户端先静态推断（`Expr.type()`）、再以
resultType 运行时交叉校验，不一致按 INTERNAL 处理。

**QueryData**:
第 1 层查询结果的密封接口（VectorData/MatrixData/ScalarData/StringData 四变体），
每个变体携带 `warnings()`。第 2 层端点返回各自的领域类型，不入此层级。

**SampleValue**:
样本值的密封接口：`FloatValue`（double）或 `HistogramValue`（native histogram）。
对应 Go `promql.Sample` 的 FPoint/HPoint 二象性。

**Native histogram（原生直方图）**:
Prometheus 2.40+ 的 histogram 样本值。线格式：`count`/`sum` + bucket 四元组
`[边界方案, 下界, 上界, 计数]`，数值均为字符串编码浮点。

**Binding（绑定）**:
Gson `JsonElement` 树 → 响应 record 的转换，全部收敛在 `ResponseBinder`。
Gson 只做树解析（无 databind、无注解）；Prometheus 特有约定——字符串编码浮点
（`"NaN"/"+Inf"` 经 GoFloat）、四元组 bucket、标签集按 name 排序——都在这一层。
_Avoid_: deserialization（指 Gson databind 反序列化——本项目明确不用）

**ErrorType**:
`status:"error"` 响应 `errorType` 字段的六类别（bad_data/timeout/canceled/
execution/unavailable/internal）；未知字面量宽容归 INTERNAL。

**RawRequest / RawResponse**:
传输无关的请求/响应描述。基类构造请求、绑定响应，唯一抽象方法 `send(RawRequest)`
由具体传输实现（默认 JDK `java.net.http.HttpClient`）映射为真实协议调用。
_Avoid_: DTO（这是内部协议描述，不是外部数据模型）

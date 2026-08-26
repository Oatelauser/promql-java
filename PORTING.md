# PORTING.md — Go → Java 移植对照与已知分歧

本库把 Prometheus 官方 Go 实现（参考快照见 `docs/promql/`，仅语法层）移植为
Java 17 的 PromQL 解析/打印库。本文记录：源文件 ↔ Java 类型映射（Q16 决定）、
逐条已知分歧、以及一致性测试的提取管线。领域术语见 [CONTEXT.md](CONTEXT.md)，
设计决策见 `docs/adr/`。

## 0. 上游版本锚点（迭代基准）

- **快照版本：Prometheus main@2026-08-25**（v3.14.0 于 2026-08-17 发布后、
  3.15 开发线起点）。**本库移植基准 = 该快照本身**（不是 tag）。
- **快照 vs v3.14.0 tag 的实测差异**（`bash scripts/check-snapshot.sh
  v3.14.0` 逐文件比对：移植范围 19 文件中 6 个有差异）：
  `parser/parse.go`（新增 `wrapParenDurationExpr` 等）、
  `parser/generated_parser.y`/`.y.go`、`parser/parse_test.go`、
  `parser/prettier.go`、`parser/printer_test.go`——即 v3.14.0 发布后合入
  main 的 parser 增量，**已随快照一并移植**（如
  `Parser.wrapParenDurationExpr`）。其余 13 文件（`lex.go`、`ast.go`、
  `printer.go`、`functions.go`、`posrange/`、`durations.go` 等）与 tag
  逐字一致。
- **duration 表达式口径**：上游 3.14.0 起 `promql-duration-expr` 特性 flag
  已成 no-op（默认启用，#19033），但那只在 cmd 接线层——parser 层
  `ExperimentalDurationExpr` 门禁（"experimental duration expression is
  not enabled"）在 v3.14.0 依然保留。本库 `ParserOptions` 默认全关，镜像
  的是 parser 层 API 默认值，而非 Prometheus server 的 CLI 默认值——这是
  有意的移植口径（调用方显式开启），非漂移。
- **后续迭代**：上游发新版本时先跑 `bash scripts/check-snapshot.sh v3.15.0`，
  输出的 DRIFT 清单 = 该版本相对本快照的上游改动（对照上表已列差异甄别
  方向）；等价于 `git diff <main@2026-08-25>..<新tag> -- promql/parser/`。
  每次同步后在本文记录新的锚点。

## 1. 范围

移植对象是 `docs/promql/parser/`（+ 少量上游 `model/labels` 语义）：词法、
语法、AST、函数表、打印。**不**含求值引擎（`engine.go`、`functions.go` 的
运行时部分、`promqltest/`）。

入口三件套（Q11）：`ParseExpr` / `ParseMetricSelector` / `ParseMetric`；
`ParseSeriesDesc` 不移植。

## 2. 文件映射表

| Go 源（docs/promql/…） | Java（com.promql.…） | 说明 |
|---|---|---|
| `parser/lex.go` | `lexer/Lexer.java`、`lexer/Item.java`、`lexer/ItemType.java` | 状态机逐字移植；三引风格（`"`/`'`/`` ` ``）与嵌套括号深度都保留 |
| `parser/generated_parser.y`（+ `.y.go`） | `parser/Parser.java` | **手写递归下降 + 优先级爬升**，非 yacc 生成（Q8，ADR-0001）；文法优先级/结合性/语义动作逐条对齐 `.y` |
| `parser/parse.go` | `parser/Parser.java`（checkAST/expectType/unexpected 等）、`parser/ParseError.java`、`parser/PromqlParseException.java` | 未检查异常携带 `List<ParseError>`（Q7） |
| `parser/parse.go` 的 `Options` | `parser/ParserOptions.java` | 4 个实验开关镜像（见 §5） |
| `parser/ast.go` | `ast/*.java` | sealed 接口 `Node`/`Expr` + 12 个 record + 枚举；`Visitor`/`Inspector`/`Walk` 对应 Go 同名设施（Q13） |
| `parser/functions.go`（函数表部分） | `functions/Function.java`、`functions/Functions.java` | 66 个函数（含实验 `mad_over_time`、`limitk`、`limit_ratio` 等） |
| `parser/printer.go` | `printer/Printer.java`（`toPromql`）、`printer/Tree.java` | `String()` 系列全量移植；`ShortString()` 按 Q10 裁剪 |
| `parser/posrange/posrange.go` | `posrange/PositionRange.java` | record；`undefined()` 对应 `PositionRange{Start: -1, End: -1}` |
| 上游 `model/labels`（快照未含） | `labels/Label.java`、`labels/LabelMatcher.java`、`labels/Labels.java`、`labels/MatchType.java` | 快照未 vendored，按上游语义移植 |
| `parser/value.go` + `value.go`（类型名部分） | `value/ValueType.java` | 仅类型标签（string/scalar/vector/matrix），无样本类型 |
| `durations.go`（时长字面量部分） | `util/DurationFormat.java` | `model.ParseDuration` 与 Go 时长格式化 |
| Go `strconv`（ParseFloat/Quote/ParseInt） | `util/GoFloat.java`、`util/GoStrings.java` | Go 语义模拟（见分歧 7） |
| 包级入口 | `Promql.java` | 门面：`parse`/`tryParse`/`parseMetricSelector`/`parseMetric`/`print` |

### 类型级对照（易踩坑处）

| Go | Java | 差异说明 |
|---|---|---|
| `time.Duration` | `long`（纳秒） | Q9：不用 JSR-310 |
| `*labels.Matcher` | `LabelMatcher` record | 纯数据（Q13）；正则编译校验移入解析器 |
| `[]string`（Grouping/Matching/Include） | `List<String>`（不可变） | Go 的 `nil` 归一为 `List.of()`（Go 打印对 nil/空同处理，见分歧 10） |
| `*float64`（Fill） | `Double`（可空包装） | — |
| `Item.Typ` 常量表 | `ItemType` 枚举 | — |
| `map[...]` 函数表 | `Functions.getFunction(name)` | — |

## 3. 已知分歧清单

按影响面排序。**1–4 是行为口径差异，5–11 是平台性差异/裁剪。**

1. **错误报告策略：首错中止 vs 全量累积。**
   Go 文法动作对语法错误也是"记录后继续"（yacc error recovery），最终返回
   完整 `ParseErrors` 列表；本库遇**语法**错误立即中止（Q7 的 unchecked 异常
   携带已累积列表），`checkAST` 类语义错误仍按 Go 累积。一致性测试因此只
   比对**第一条**错误的位置与消息。**首错的"内容"已全量对齐 Go**（差分
   模糊 4935 条 0 分歧，见 §4），关键移植点：goyacc 的归约动作发生在
   移进构造尾 token 之后、拉取前瞻**之前**——语义检查（未知函数、
   no-arguments、@/offset/range 前置条件等）与节点 End（`lastClosing`）
   都在读取前瞻前完成（解析器"停右括号约定"）；词法 ERROR 则"记录后
   按EOF继续、后续语法错误静默"（Go `parser.Error` 为空操作 +
   `unexpected` 跳过 ERROR 项的合成本）。

2. **位置偏移：UTF-16 代码单元 vs 字节。**
   Go `PositionRange` 是字节偏移；Java `String` 索引是 UTF-16 代码单元。
   含多字节字符（CJK、emoji）的输入两者数值可能不同（BMP 内一致，增补平面
   差 1/2 字符）。ASCII 输入完全一致；当前官方用例集内无因该差异失败的条目。

3. **无效 UTF-8 输入不可表达。**
   Go 字符串是字节串，`parse_test.go` 有 2 条用 `\xff` 构造的 fail 用例
   （"invalid UTF-8 rune"）；Java `String` 无法承载非法 UTF-8 字节序列，
   提取器已剔除这两条（`extract_cases.py` 输出 `dropped_utf8 2`）。

4. **深递归受 JVM 栈限制（不设阈值，解法＝加大栈）。**
   Go 协程栈可动态增长，官方压力用例（万层 `-{}-1` 链 + 千层 `[1m:]`）在
   Go 中可行；JVM 线程栈固定。真实 PromQL 深度极浅（官方用例表实测最深
   3 层），该限制只影响病态/恶意输入。**决议：不加深度阈值**——保持与 Go
   同等的"看平台资源"能力（Go 看内存，Java 看栈），超深输入以
   `StackOverflowError` 失败，解法是加大解析线程的栈：

   - JVM 全局：启动参数 `java -Xss32m`；
   - 单线程：`new Thread(null, task, "promql-parser", 32L << 20)`
     （第 4 参即 stackSize）；
   - 线程池：`ThreadFactory` 内统一走上述构造，再交给
     `Executors.newFixedThreadPool(n, factory)`。

   测试侧的 surefire `-Xss32m` 即该解法的应用（`promql-core/pom.xml`）。
   消费方（`Printer`、`Walk`/`Inspector`）递归深度与 AST 深度同阶，同样适用。

5. **打印回读：非 legacy 标签名加引号（快照反推）。**
   上游 `labels` 包未 vendored，`Matcher.String()` 的行为由快照
   `printer_test.go` 黄金用例反推：非 legacy 标签名（`{"a.b"="c"}`、
   `{"0"="1"}`、`{""="0"}`）**用引号包裹**，legacy 名（`{"_0"="1"}` →
   `{_0="1"}`）裸写——与 printer `writeLabels` 的分组标签同一判定。
   初版曾按旧版上游实现裸写，被 `PrinterGoldenTest` 的黄金用例发现并修复；
   修复后含 `a\dos\path` 标签名的 2 条 parse_test 用例也可打印幂等，
   全部 352 条无排除项。

6. **正则方言：RE2 vs `java.util.regex`。**
   标签匹配器的正则语法校验用 Java `Pattern.compile`。常见 POSIX 语法重叠；
   个别构造不同（命名分组 `(?P<name>)` vs `(?<name>)`、RE2 不支持的反向引用
   在 Java 中合法）；**错误文本也不同**（RE2
   ``missing argument to repetition operator: `*` `` vs Java
   `Dangling meta character '*'`）。语义求值不在本库范围（Q13 纯数据）。
   差分模糊测试对该类首错取**前缀容差**（双方均以
   `error parsing regexp:` 开头即视为同类），语料中 2 条按此口径放行。

7. **浮点解析细节。**
   `GoFloat.parseFloat` 模拟 `strconv.ParseFloat`：接受 `Inf`/`Infinity`/
   `NaN` 字面量；数值上溢报 "value out of range"（与 Go 一致）；下溢
   （如 `1e-400`）Go 返回 0 且不报错，Java `Double.parseDouble` 同为 0，
   行为一致但实现的舍入路径不同。`parseGoInt64` 支持 0x/0b/0o/前导 0 八进制。
   格式化侧（`formatFloatF/G`）以 **Go 真 oracle 黄金向量**背书：
   `scripts/gen_gofloat_vectors.go`（固定种子）生成 10049 条边界+随机向量
   （`gofloat_vectors.tsv`），`GoFloatVectorTest` 全量比对 `'f'`/`'g'`
   输出并做解析往返。该向量集首次接入即抓出三处手工推导错误并已修复：
   负数小数指数偏移一位（`-123456.789` 误入科学分支）、次正规数最短位数
   （Java "4.9E-324" vs Go "5E-324"）、无小数点形态的科学指数（`1e6`
   误作 `1e+04`）——正是 B11 存在的理由。
   `shortest()` 含快速路径：优先采信 JDK 19+ `Double.toString`（Ryū，与
   Go ftoa 同一「最短 + 最近 + 平局偶舍入」规则），但**两重校验**不过即
   退回 BigDecimal 逐档循环（裁判谓词与原实现逐字相同）：候选须精确回读
   `v`，且 `k-1` 位收紧不可区分——后者防 JDK 17 运行时旧 FloatingDecimal
   偶发多一位（如次正规 "4.9E-324" vs Go "5E-324"）。黄金向量全量通过，
   混合语料实测约 4×（JDK 25，`FormatBenchmark`）。

8. **`ShortString()` 裁剪。**
   Go printer.go 的 `ShortString` 系列（调试用途，快照内无生产调用方）按
   Q10 不移植；`Tree()` 调试输出保留。

9. **`ParseSeriesDesc` 不移植**（Q11）：其文法与 `lexSeriesDesc` 一并裁剪。

10. **nil 归一化。**
    Go 构造器允许 nil 切片字段；Java record 的 `List.copyOf` 拒绝 null，
    解析器在装配前把 `nil` Grouping/MatchingLabels/Include 归一为空列表。
    已核对 Go printer 对 nil/空切片输出相同（`case node.Without` /
    `case len(node.Grouping) > 0`），语义无损。

11. **时长字面量上界。**
    `MAX_DURATION_LITERAL_SECONDS = math.MaxInt64 纳秒折算秒`（≈9.22e9），
    超界报 "duration out of range"，与 Go 相同；Java 侧用 double 比较。

## 4. 测试与提取管线

```
docs/promql/parser/parse_test.go ──extract_cases.py──▶ parse_test_cases.tsv
        │                                                     │
        │                                        ParserConformanceTest（352 条）
        ├── testExpr 抽样 ─────────────▶ ParserAstConformanceTest（22 例）
        │                                  + StringToAstTest（45 例 = 37 正向构造
        │                                    + 8 组实战查询双向往返）
        └── printer_test.go ──手工移植──▶ PrinterGoldenTest（92 例，打印黄金输出）
                                           + AstToStringTest（14 组，手造 AST→字符串）

scripts/gen_gofloat_vectors.go ──go run──▶ gofloat_vectors.tsv（10049 条）
                                                    │
                                        GoFloatVectorTest（Go strconv 黄金向量）

scripts/fuzz-oracle/main.go ──go run .──▶ fuzz_diff_cases.tsv（4935 条差分语料）
        │   （Go oracle = 上游 parser 同 commit，突变+文法随机游走+病态探针，
        │    语料强制 ASCII：Go 字节偏移 == Java UTF-16 索引，分歧 2 不触发）
        │   probe/（多错误探针）、tree/（AST 区间转储）为排查工具，不产语料
        └──▶ ParserFuzzDifferentialTest（全量重放：ok 行打印逐字相等
             + fail 行首错位置/消息逐字相等；分歧 6 正则错误按前缀容差）
```

- `extract_cases.py`：解析 Go `testExpr` 表（含 `fmt.Sprintf`/`strings.Repeat`
  输入、`repeatError` 期望、多行错误块），产出 TSV——列：
  `fail(0/1)`、`errStart`、`errEnd`、`errMsg`、`input`（制表符分隔，
  `\\`/`\t`/`\n`/`\r` 转义）。当前 **352 条**（206 成功 / 146 失败；剔除
  2 条无效 UTF-8）。
- `ParserConformanceTest`：成功用例 = 解析成功 + **打印再解析稳定**
  （`print(parse(print(ast))) == print(ast)`，Q4 的 P1 口径）；失败用例 =
  抛 `PromqlParseException` 且首错位置与消息逐字相等。选项与 Go
  `TestParseExpressions` 相同（四个实验开关全开）。
- `ParserAstConformanceTest`：22 例覆盖全部节点类型的**逐字段** AST 比对
  （record equals 按 Q9 忽略位置字段）+ 少量位置区间断言。
- `StringToAstTest`（字符串→AST）：37 条正向构造表，期望 AST 全部用静态
  工厂手工搭建（含十六进制数字、负 offset、四种匹配器、bool、fill、
  时长表达式等官方抽样未展开的形态）；另有 8 组**实战规模**完整查询
  （多行 + 注释 + 嵌套聚合/子查询/匹配子句，含一组实验语法全家桶）做
  字符串→AST→字符串→AST→字符串的双向往返断言，`main` 方法演示全流程。
- `PrinterGoldenTest`（打印黄金输出）：`TestExprString` 86 条 +
  `TestBinaryExprUTF8Labels` 6 条逐字移植——断言打印**内容**（而非幂等）。
- `AstToStringTest`（AST→字符串）：14 组手造 AST 直接喂 `Printer`，含
  `TestVectorSelector_String` 8 例；全程不经过解析器。
- `scripts/fuzz-oracle`（差分模糊，Go oracle）：以官方 352 条为种子的确定性
  突变（每种子 12 条：删/插/换/交换/截断/重复/尾接/双重）+ 文法随机游走
  （700 条，深度≤5）+ 病态深度探针（括号/二元链/子查询/时长表达式嵌套），
  共 **4935 条**（1173 ok / 3762 fail），逐条记录 Go 的判定——ok 行含
  `expr.String()` 打印输出，fail 行含首错位置与消息。TSV 六列：
  `ok(1/0)`、`errStart`、`errEnd`、`errMsg`、`input`、`printed`（转义
  同上）。`ParserFuzzDifferentialTest` 全量重放（资源缺失时跳过）；再生成
  需 Go 工具链：`cd scripts/fuzz-oracle && go run .`（产物入库，提交前
  复跑差分测试）。该语料接入后驱动了 7 类根因修复（ItemTypeStr 的
  NUMBER 误并、GoFloat 错误文本、归约前检查/End 快照、词法 ERROR 挂起、
  @ 修饰符 amd64 溢出语义、offset 嵌套一元、聚合错误上下文），是本库
  除官方用例外最强的一致性背书。
- 词法器无独立单测，由上述用例间接全覆盖；打印器有独立单测（后三类）。

## 5. 特性开关映射

| Prometheus `--enable-feature` | `ParserOptions` 字段 |
|---|---|
| `promql-experimental-functions` | `enableExperimentalFunctions` |
| `promql-experimental-duration-expr` | `experimentalDurationExpr` |
| `promql-experimental-extended-range-selectors` | `enableExtendedRangeSelectors` |
| `promql-experimental-binop-fill-modifiers` | `enableBinopFillModifiers` |

默认全关（`ParserOptions.defaults()`），与 Prometheus 主程序一致。

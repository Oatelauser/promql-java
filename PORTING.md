# PORTING.md — Go → Java 移植对照与已知分歧

本库把 Prometheus 官方 Go 实现（参考快照见 `docs/promql/`，仅语法层）移植为
Java 17 的 PromQL 解析/打印库。本文记录：源文件 ↔ Java 类型映射（Q16 决定）、
逐条已知分歧、以及一致性测试的提取管线。领域术语见 [CONTEXT.md](CONTEXT.md)，
设计决策见 `docs/adr/`。

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
   比对**第一条**错误的位置与消息（352 条官方用例全过）。

2. **位置偏移：UTF-16 代码单元 vs 字节。**
   Go `PositionRange` 是字节偏移；Java `String` 索引是 UTF-16 代码单元。
   含多字节字符（CJK、emoji）的输入两者数值可能不同（BMP 内一致，增补平面
   差 1/2 字符）。ASCII 输入完全一致；当前官方用例集内无因该差异失败的条目。

3. **无效 UTF-8 输入不可表达。**
   Go 字符串是字节串，`parse_test.go` 有 2 条用 `\xff` 构造的 fail 用例
   （"invalid UTF-8 rune"）；Java `String` 无法承载非法 UTF-8 字节序列，
   提取器已剔除这两条（`extract_cases.py` 输出 `dropped_utf8 2`）。

4. **深递归受 JVM 栈限制。**
   Go 协程栈可动态增长，官方压力用例（万层 `-{}-1` 链 + 千层 `[1m:]`）在
   Go 中可行；JVM 线程栈固定，`mvn test` 已在 surefire 配置 `-Xss32m`。
   库使用者解析极深表达式时可能需要 `new Thread(null, r, "parser", 32 << 20)`。

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
   在 Java 中合法）。语义求值不在本库范围（Q13 纯数据）。

7. **浮点解析细节。**
   `GoFloat.parseFloat` 模拟 `strconv.ParseFloat`：接受 `Inf`/`Infinity`/
   `NaN` 字面量；数值上溢报 "value out of range"（与 Go 一致）；下溢
   （如 `1e-400`）Go 返回 0 且不报错，Java `Double.parseDouble` 同为 0，
   行为一致但实现的舍入路径不同。`parseGoInt64` 支持 0x/0b/0o/前导 0 八进制。

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
- 词法器无独立单测，由上述用例间接全覆盖；打印器有独立单测（后三类）。

## 5. 特性开关映射

| Prometheus `--enable-feature` | `ParserOptions` 字段 |
|---|---|
| `promql-experimental-functions` | `enableExperimentalFunctions` |
| `promql-experimental-duration-expr` | `experimentalDurationExpr` |
| `promql-experimental-extended-range-selectors` | `enableExtendedRangeSelectors` |
| `promql-experimental-binop-fill-modifiers` | `enableBinopFillModifiers` |

默认全关（`ParserOptions.defaults()`），与 Prometheus 主程序一致。

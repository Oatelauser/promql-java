# promql-java

PromQL 解析/打印库（Java 17，零运行时依赖）：**PromQL 字符串 ⇄ AST**。
移植自 Prometheus 官方 Go 实现的语法层（参考快照 `docs/promql/`），一致性以
官方 `parse_test.go` 全表背书。

```java
Expr ast = Promql.parse("sum(rate(foo[5m])) by (job)");
// 打印为规范化 PromQL（P1 契约：parse(print(ast)) 与 ast 语义等价）
String query = Promql.print(ast);      // "sum by (job) (rate(foo[5m]))"

// 不抛异常形态
Expr maybe = Promql.tryParse("foo{a=");   // null

// 实验语法需显式开启（与 Prometheus --enable-feature 对应）
ParserOptions opts = new ParserOptions(
        /* experimentalFunctions */ true,
        /* durationExpr       */ true,
        /* extendedRange      */ true,
        /* binopFill          */ true);
Expr sq = Promql.parse("foo[2m+3m:step()] offset -(10s-5s)", opts);
```

## 特性

- **全量语法**：官方快照的完整 PromQL 文法——选择器/矩阵/子查询/二元与集合
  运算/聚合/函数/`@` 与 `offset`/时长算术表达式/`anchored`/`smoothed`/
  `fill` 修饰符/UTF-8 标签名（含三种引号风格）。
- **P1 往返契约**：`parse(print(ast))` 与 `ast` 语义等价（比对以打印输出为准）；
  不承诺 P2 字节稳定（打印是规范化形式）。见 `docs/adr/0002`。
- **语法纯 AST**（`docs/adr/0003`）：sealed 接口 + 不可变 record，位置字段
  不参与 equals/hashCode；`Visitor`/`Inspector`/`Walk` 遍历设施齐备。
- **Go 对齐的错误信息**：`PromqlParseException`（unchecked）携带
  `List<ParseError>`，首错的位置与消息与 Go 逐字一致。
- **零运行时依赖**；仅测试作用域 JUnit 5。

## 快速上手

要求：JDK 17+（工具链 21 时按 release 17 编译）。

```bash
mvn test          # 525 用例：352 官方表 + 22 AST 抽样 + 45 字符串→AST
                  #         + 92 打印黄金 + 14 AST→字符串
mvn package       # target/promql-java-0.1.0.jar
```

Maven 坐标（本仓库自身；安装到本地库 `mvn install` 后可用）：

```xml
<dependency>
    <groupId>com.promql</groupId>
    <artifactId>promql-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

主要入口见 `com.promql.Promql`（门面）与 `com.promql.parser.Parser`。
实验开关见 `ParserOptions`（默认全关）。

## 与 Go 实现的关系

- 移植范围与源文件映射：[PORTING.md](PORTING.md)
- 领域术语表：[CONTEXT.md](CONTEXT.md)；架构决策：`docs/adr/`
- 已知分歧（首错中止、UTF-16 位置口径、深递归栈等 11 条）：PORTING.md §3

## 目录结构

```
src/main/java/com/promql/
├── Promql.java            # 门面：parse / tryParse / parseMetricSelector / parseMetric / print
├── ast/                   # sealed AST（record + 枚举 + Visitor/Inspector/Walk）
├── parser/                # Parser（递归下降+优先级爬升）、ParserOptions、异常
├── lexer/                 # 词法状态机（Lexer/Item/ItemType）
├── printer/               # Printer.toPromql（规范化输出）+ Tree（调试树）
├── functions/             # 66 函数表
├── labels/                # Label/LabelMatcher/MatchType/Labels（上游 model/labels 语义）
├── posrange/              # PositionRange
├── util/                  # GoFloat/GoStrings/DurationFormat（Go 语义模拟）
└── value/                 # ValueType
src/test/                  # 双向测试：
│   parser/                #   ParserConformanceTest（352 官方表）
│                          #   ParserAstConformanceTest（22 逐字段抽样）
│                          #   StringToAstTest（37 字符串→AST + 8 组实战双向转换，
│                          #                 main 方法可演示字符串⇄AST 全流程）
│   printer/               #   PrinterGoldenTest（92 打印黄金输出）
│                          #   AstToStringTest（14 组 AST→字符串）
extract_cases.py           # parse_test.go → parse_test_cases.tsv 提取器
```

## 许可

Apache License 2.0（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)）。
上游：[Prometheus](https://github.com/prometheus/prometheus)（Apache License 2.0）。

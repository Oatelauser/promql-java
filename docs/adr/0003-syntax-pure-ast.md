# AST 为纯语法对象，裁掉引擎/存储字段

参考实现的 AST 节点混有求值引擎与存储层字段。移植裁剪：**丢弃** `StepInvariantExpr`（引擎步进优化包装，解析器从不产出）、`TestStmt`（测试桩）、`VectorSelector` 的 `Series`/`UnexpandedSeriesSet`/`BypassEmptyMatcherCheck`（执行期字段）、`EvalStmt`（引擎包装，`ParseExpr` 产出的是 `Expr`）；**保留** 解析器产出的全部字段（`@` 修饰符 Timestamp、offset 族、`SkipHistogramBuckets`/`Anchored`/`Smoothed`、`DurationExpr` 等，实现时逐一对照快照源码确认归属）。

理由：本库是解析/打印库（非求值引擎，见 CONTEXT.md "Syntax purity"）；执行期字段没有 Java 对应物（`storage.SeriesSet`），镜像它们只会引入空字段与假 API。将来若做求值引擎，其状态另行存放于执行层对象，不回填 AST。

## Consequences

- 从本 AST 出发做引擎移植的人会找不到 `StepInvariantExpr`/`Series` —— 本 ADR 即是答案：这是决定，不是遗漏。
- 打印器无需处理任何执行期字段，输出 = 纯语法渲染。

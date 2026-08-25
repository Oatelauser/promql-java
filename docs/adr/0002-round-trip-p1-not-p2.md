# 往返契约只承诺再解析稳定性（P1），不承诺字节稳定性（P2）

库承诺 **P1**：`parse(print(ast))` 与 `ast` 语义等价（验证方式与 Go 相同——比对打印输出，而非对象 equals）。**明确不承诺 P2**：`print(parse(q)) == q` 逐字节还原。因为打印是规范化输出（label matcher 按字母排序、空白与引号格式统一——忠实移植 Go printer 行为），而 AST 不保留原始 token；Go 参考实现同样不保证 P2。

## Considered Options

- 保证 P2：要求 AST 保留原始 token/原文切片，等于在语法树里塞进词法层状态，污染 ADR-0003 定下的语法纯度，且与参考实现行为分叉。否决。
- 什么都不承诺：不可接受——"基于 AST 构建 PromQL" 是本库的存在理由，P1 是其最低正确性契约。

## Consequences

- 文档与 Javadoc 必须显式写明 P2 不成立，防止使用者拿打印输出做字符串 diff 当作输入指纹。
- 测试：conformance 金标准（移植 parse_test.go 期望输出）+ 打印幂等性（`print(parse(print(parse(q)))) == print(parse(q))`）。

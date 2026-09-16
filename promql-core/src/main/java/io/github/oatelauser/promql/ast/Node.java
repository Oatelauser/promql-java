package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.posrange.PositionRange;
import io.github.oatelauser.promql.printer.Printer;
import io.github.oatelauser.promql.printer.Tree;

/**
 * AST 节点的根接口，对应 Go {@code parser/ast.go} 的 {@code Node}。
 *
 * <p>sealed 层次（Q9）：本库的全部节点类型恰好 11 个（都经由
 * {@link Expr}），编译期封闭。{@link #toPromql()} 与 {@link #tree()} 是
 * 默认方法，逻辑集中在打印器（对应 Go 各节点的 {@code String()} 与
 * {@code Tree()} 方法），AST 类型本身保持纯数据（Q5 语法纯度）。
 */
public sealed interface Node permits Expr {

    /**
     * 节点在源文本中的位置区间（不参与 equals，见 CONTEXT.md “Position range”）。
     */
    PositionRange positionRange();

    /**
     * 渲染为规范化 PromQL 字符串（Go {@code Node.String()}）。
     */
    default String toPromql() {
        return Printer.toPromql(this);
    }

    /**
     * 渲染为多行调试树（Go {@code Node.Tree()}）。
     */
    default String tree() {
        return Tree.print(this);
    }

    /**
     * 合并两个节点的位置区间（前者的起点 + 后者的终点），对应 Go
     * {@code mergeRanges}。参数顺序必须与它们在输入串中的顺序一致。
     */
    static PositionRange mergeRanges(Node first, Node last) {
        return new PositionRange(first.positionRange().start(), last.positionRange().end());
    }
}

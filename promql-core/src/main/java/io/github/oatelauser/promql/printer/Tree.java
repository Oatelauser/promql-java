package io.github.oatelauser.promql.printer;

import io.github.oatelauser.promql.ast.Node;
import io.github.oatelauser.promql.ast.Walk;

/**
 * AST 的多行调试树渲染，对应 Go {@code parser/printer.go} 的
 * {@code Tree(node)} / {@code tree(node, level)}。
 *
 * <p>输出形如（逐行与 Go 一致，包括 {@code " |---- "} 前缀与层级缩进
 * {@code " · · ·"}）：
 *
 * <pre>{@code
 *  |---- BinaryExpr :: foo + bar
 *  · · · |---- VectorSelector :: foo
 *  · · · |---- VectorSelector :: bar
 * }</pre>
 *
 * <p>Go 用 {@code %T}（"*parser.BinaryExpr" 取 "." 后段）得到类型名；
 * Java 用 {@link Class#getSimpleName()}，二者对全部 11 个节点类型同名。
 * nil 节点渲染为 {@code <nil>}（Go {@code fmt.Sprintf("%T", nil Node)} 的
 * 输出）。
 */
public final class Tree {

    private Tree() {
    }

    /**
     * 对应 Go {@code Tree(node)}。
     */
    public static String print(Node node) {
        return tree(node, "");
    }

    private static String tree(Node node, String level) {
        if (node == null) {
            return level + " |---- <nil>\n";
        }
        String typs = node.getClass().getSimpleName();
        StringBuilder t = new StringBuilder();
        t.append(level).append(" |---- ").append(typs)
                .append(" :: ").append(node.toPromql()).append('\n');
        level += " · · ·";
        for (Node e : Walk.children(node)) {
            t.append(tree(e, level));
        }
        return t.toString();
    }
}

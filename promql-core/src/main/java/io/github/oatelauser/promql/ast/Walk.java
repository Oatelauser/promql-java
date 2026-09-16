package io.github.oatelauser.promql.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 深度优先遍历，对应 Go {@code parser/ast.go} 的 {@code Walk}、
 * {@code Inspect}、{@code ChildrenIter}、{@code Children}。
 *
 * <p>遍历顺序：先序访问节点；返回的 Visitor 非空时依次递归各子节点；
 * 子树结束后回调 {@code visit(null, null)}（Go 语义原样保留）。
 */
public final class Walk {

    private Walk() {
    }

    /**
     * 对应 Go {@code Walk(v, node, path)}。
     */
    public static void walk(Visitor v, Node node, List<Node> path) {
        Visitor w = v.visit(node, path);
        if (w == null) {
            return;
        }
        List<Node> pathToHere = null;
        for (Node child : children(node)) {
            if (pathToHere == null) {
                pathToHere = new ArrayList<>(path);
                pathToHere.add(node);
            }
            walk(w, child, pathToHere);
        }
        w.visit(null, null);
    }

    /**
     * 对应 Go {@code Inspect(node, f)}：每节点回调一次 f，遍历全部子树。
     */
    public static void inspect(Node node, Inspector f) {
        walk(new InspectorVisitor(f), node, new ArrayList<>());
    }

    /**
     * 对应 Go 的 {@code inspector} 适配器：调用 f 后总是返回自身以继续遍历。
     */
    private static final class InspectorVisitor implements Visitor {
        private final Inspector f;

        InspectorVisitor(Inspector f) {
            this.f = f;
        }

        @Override
        public Visitor visit(Node n, List<Node> path) {
            f.inspect(n, path);
            return this;
        }
    }

    /**
     * 子节点列表，对应 Go {@code Children(node)}；顺序与 Go
     * {@code ChildrenIter} 一致：AggregateExpr→[expr, param]，
     * BinaryExpr→[lhs, rhs]，Call→args，Subquery/Paren/Unary/Matrix→单个子。
     *
     * <p>注意：与 Go 完全一致，{@link DurationExpr} <b>没有</b>子节点遍历
     * （Go 在此处 panic），遇之抛 {@link IllegalStateException}。
     */
    public static List<Node> children(Node node) {
        List<Node> res = new ArrayList<>(2);
        if (node instanceof AggregateExpr a) {
            if (a.expr() != null) {
                res.add(a.expr());
            }
            if (a.param() != null) {
                res.add(a.param());
            }
        } else if (node instanceof BinaryExpr b) {
            res.add(b.lhs());
            res.add(b.rhs());
        } else if (node instanceof Call c) {
            res.addAll(c.args());
        } else if (node instanceof SubqueryExpr s) {
            res.add(s.expr());
        } else if (node instanceof ParenExpr p) {
            res.add(p.expr());
        } else if (node instanceof UnaryExpr u) {
            res.add(u.expr());
        } else if (node instanceof MatrixSelector m) {
            res.add(m.vectorSelector());
        } else if (node instanceof NumberLiteral || node instanceof StringLiteral
                || node instanceof VectorSelector) {
            // 叶子：无子节点
        } else {
            throw new IllegalStateException("ChildrenIter: unhandled node type " + node.getClass().getName());
        }
        return res;
    }
}

package com.promql.ast;

import java.util.List;

/**
 * Inspect 使用的简化访问函数，对应 Go {@code parser/ast.go} 的
 * {@code inspector}。每个节点回调一次（含子树结束时的 null 回调）。
 */
@FunctionalInterface
public interface Inspector {

    /**
     * @param node 当前节点；子树结束时回调 {@code null}
     * @param path 祖先路径（不含当前节点）
     */
    void inspect(Node node, List<Node> path);
}

package io.github.oatelauser.promql.ast;

import java.util.List;

/**
 * AST 遍历的访问者，对应 Go {@code parser/ast.go} 的 {@code Visitor}。
 *
 * <p>Go 版返回 {@code (Visitor, error)}；本库的遍历不产生错误，仅保留
 * “返回 null 即停止下探”的语义（对应 Go 返回 nil Visitor）。
 */
public interface Visitor {

    /**
     * 访问节点。
     *
     * @param node 当前节点；子树遍历结束时以 {@code (null, null)} 回调一次
     *             （与 Go {@code Walk} 的收尾 {@code Visit(nil, nil)} 一致）
     * @param path 从根到当前节点的祖先路径（不含当前节点）；回调可能复用
     *             缓冲，需要保留时请自拷贝（同 Go 的注意项）
     * @return 用于子树的访问者；null 表示不再下探
     */
    Visitor visit(Node node, List<Node> path);
}

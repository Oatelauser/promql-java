package io.github.oatelauser.promql.ast;

import io.github.oatelauser.promql.value.ValueType;

/**
 * 表达式节点接口，对应 Go {@code parser/ast.go} 的 {@code Expr}。
 *
 * <p>Go 里 {@code Expressions}（[]Expr 切片）与 {@code EvalStmt} 也是 Node，
 * 本库按 ADR-0003 裁掉：函数实参直接用 {@code List<Expr>}，不设独立节点。
 * 因此本库全部 11 种节点都是 Expr。
 */
public sealed interface Expr extends Node
        permits AggregateExpr, BinaryExpr, Call, DurationExpr, MatrixSelector,
        NumberLiteral, ParenExpr, StringLiteral, SubqueryExpr, UnaryExpr,
        VectorSelector {

    /**
     * 表达式的求值结果类型，对应 Go {@code Node.Type()}。
     */
    ValueType type();
}

package com.promql.printer;

import com.promql.ast.AggregateExpr;
import com.promql.ast.AggregateOp;
import com.promql.ast.BinaryExpr;
import com.promql.ast.Call;
import com.promql.ast.DurationExpr;
import com.promql.ast.DurationOp;
import com.promql.ast.Expr;
import com.promql.ast.MatrixSelector;
import com.promql.ast.Node;
import com.promql.ast.NumberLiteral;
import com.promql.ast.ParenExpr;
import com.promql.ast.StartOrEnd;
import com.promql.ast.StringLiteral;
import com.promql.ast.SubqueryExpr;
import com.promql.ast.UnaryExpr;
import com.promql.ast.VectorMatchCardinality;
import com.promql.ast.VectorMatching;
import com.promql.ast.VectorSelector;
import com.promql.labels.LabelMatcher;
import com.promql.labels.Labels;
import com.promql.labels.MatchType;
import com.promql.util.DurationFormat;
import com.promql.util.GoFloat;
import com.promql.util.GoStrings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AST → 规范化 PromQL 字符串，对应 Go {@code parser/printer.go} 的各
 * {@code String()} 方法（本库 P1 契约：{@code parse(print(ast))} 与
 * {@code ast} 语义等价，比对以打印输出为准）。
 *
 * <p>逐节点对照移植；对 Go 的三处结构性差异：
 * <ul>
 *   <li>方法分派从接口方法改为 instanceof 链（AST 记录保持纯数据，Q5）；</li>
 *   <li>MatrixSelector 的"复制后清零"用新构造 VectorSelector 记录实现
 *       （Go 值拷贝 + 字段清零，Java 记录不可变）；</li>
 *   <li>{@code %v} fill 值、{@code 'f',-1} 数字、{@code %.3f} @ 时间戳分别
 *       由 {@link GoFloat} 与 {@link String#format} 承担（Go 与 Java 的
 *       浮点格式化差异）。</li>
 * </ul>
 *
 * <p>{@code ShortString()} 系列按 Q10 裁剪（快照内无生产调用方）。
 */
public final class Printer {

    private Printer() {
    }

    /**
     * 入口，对应 Go {@code Node.String()} 的动态分派。
     */
    public static String toPromql(Node node) {
        if (node instanceof AggregateExpr n) {
            return aggregateExpr(n);
        }
        if (node instanceof BinaryExpr n) {
            return binaryExpr(n);
        }
        if (node instanceof Call n) {
            return call(n);
        }
        if (node instanceof DurationExpr n) {
            return durationExpr(n);
        }
        if (node instanceof MatrixSelector n) {
            return matrixSelector(n);
        }
        if (node instanceof NumberLiteral n) {
            return numberLiteral(n);
        }
        if (node instanceof ParenExpr n) {
            return parenExpr(n);
        }
        if (node instanceof StringLiteral n) {
            return stringLiteral(n);
        }
        if (node instanceof SubqueryExpr n) {
            return subqueryExpr(n);
        }
        if (node instanceof UnaryExpr n) {
            return unaryExpr(n);
        }
        if (node instanceof VectorSelector n) {
            return vectorSelector(n);
        }
        throw new IllegalStateException("unhandled node type: " + node.getClass().getName());
    }

    /**
     * 对应 Go {@code (Expressions).String()}：0→""、1→单元素、多→", " 连接。
     */
    static String expressions(List<? extends Expr> es) {
        switch (es.size()) {
            case 0:
                return "";
            case 1:
                return es.get(0).toPromql();
            default:
                StringBuilder b = new StringBuilder();
                b.append(es.get(0).toPromql());
                for (int i = 1; i < es.size(); i++) {
                    b.append(", ").append(es.get(i).toPromql());
                }
                return b.toString();
        }
    }

    // ------------------------------------------------------------------
    // AggregateExpr
    // ------------------------------------------------------------------

    private static String aggregateExpr(AggregateExpr node) {
        StringBuilder b = new StringBuilder();
        writeAggOpStr(b, node);
        b.append('(');
        if (node.op().isAggregatorWithParam()) {
            b.append(node.param().toPromql());
            b.append(", ");
        }
        b.append(node.expr().toPromql());
        b.append(')');
        return b.toString();
    }

    private static void writeAggOpStr(StringBuilder b, AggregateExpr node) {
        b.append(node.op().symbol());
        if (node.without()) {
            b.append(" without (");
            writeLabels(b, node.grouping());
            b.append(") ");
        } else if (!node.grouping().isEmpty()) {
            b.append(" by (");
            writeLabels(b, node.grouping());
            b.append(") ");
        }
    }

    private static void writeLabels(StringBuilder b, List<String> ss) {
        for (int i = 0; i < ss.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            String s = ss.get(i);
            // Go writeLabels 用 LegacyValidation 判定（非 legacy 名加引号，保证可回读）。
            if (!Labels.isValidLegacyLabelName(s)) {
                b.append(GoStrings.quote(s));
            } else {
                b.append(s);
            }
        }
    }

    // ------------------------------------------------------------------
    // BinaryExpr
    // ------------------------------------------------------------------

    private static String binaryExpr(BinaryExpr node) {
        String matching = getMatchingStr(node);
        return node.lhs().toPromql() + " " + node.op().symbol()
                + returnBool(node) + matching + " " + node.rhs().toPromql();
    }

    private static String returnBool(BinaryExpr node) {
        return node.returnBool() ? " bool" : "";
    }

    private static String getMatchingStr(BinaryExpr node) {
        String matching = "";
        StringBuilder b = new StringBuilder();
        VectorMatching vm = node.vectorMatching();
        if (vm != null) {
            if (!vm.matchingLabels().isEmpty() || vm.on()
                    || vm.card() == VectorMatchCardinality.MANY_TO_ONE
                    || vm.card() == VectorMatchCardinality.ONE_TO_MANY) {
                String vmTag = vm.on() ? "on" : "ignoring";
                b.append(" ").append(vmTag).append(" (");
                writeLabels(b, vm.matchingLabels());
                b.append(")");
                matching = b.toString();
            }

            if (vm.card() == VectorMatchCardinality.MANY_TO_ONE
                    || vm.card() == VectorMatchCardinality.ONE_TO_MANY) {
                String vmCard = vm.card() == VectorMatchCardinality.MANY_TO_ONE ? "left" : "right";
                b.setLength(0);
                b.append(" group_").append(vmCard).append(" (");
                writeLabels(b, vm.include());
                b.append(")");
                matching += b.toString();
            }

            if (vm.fillLhs() != null || vm.fillRhs() != null) {
                if (vm.fillLhs() != null && vm.fillRhs() != null
                        && vm.fillLhs().doubleValue() == vm.fillRhs().doubleValue()) {
                    matching += String.format(Locale.ROOT, " fill (%s)", GoFloat.formatFloatG(vm.fillLhs()));
                } else {
                    if (vm.fillLhs() != null) {
                        matching += String.format(Locale.ROOT, " fill_left (%s)", GoFloat.formatFloatG(vm.fillLhs()));
                    }
                    if (vm.fillRhs() != null) {
                        matching += String.format(Locale.ROOT, " fill_right (%s)", GoFloat.formatFloatG(vm.fillRhs()));
                    }
                }
            }
        }
        return matching;
    }

    // ------------------------------------------------------------------
    // DurationExpr
    // ------------------------------------------------------------------

    private static String durationExpr(DurationExpr node) {
        StringBuilder b = new StringBuilder(64);
        writeTo(b, node);
        return b.toString();
    }

    private static void writeTo(StringBuilder b, DurationExpr node) {
        if (node.wrapped()) {
            b.append('(');
        }

        if (node.op() == DurationOp.STEP) {
            b.append("step()");
        } else if (node.op() == DurationOp.RANGE) {
            b.append("range()");
        } else if (node.op() == DurationOp.MIN_OF) {
            b.append("min_of(").append(node.lhs().toPromql())
                    .append(", ").append(node.rhs().toPromql()).append(')');
        } else if (node.op() == DurationOp.MAX_OF) {
            b.append("max_of(").append(node.lhs().toPromql())
                    .append(", ").append(node.rhs().toPromql()).append(')');
        } else if (node.lhs() == null) {
            // 一元时长表达式。
            if (node.op() == DurationOp.SUB) {
                b.append(node.op().symbol()).append(node.rhs().toPromql());
            } else if (node.op() == DurationOp.ADD) {
                b.append(node.rhs().toPromql());
            } else {
                // Go: This should never happen.
                throw new IllegalStateException("unexpected unary duration expression: " + node.op());
            }
        } else {
            b.append(node.lhs().toPromql()).append(' ')
                    .append(node.op().symbol()).append(' ')
                    .append(node.rhs().toPromql());
        }

        if (node.wrapped()) {
            b.append(')');
        }
    }

    // ------------------------------------------------------------------
    // Call / MatrixSelector / SubqueryExpr
    // ------------------------------------------------------------------

    private static String call(Call node) {
        return node.function().name() + "(" + expressions(node.args()) + ")";
    }

    private static String matrixSelector(MatrixSelector node) {
        // Go atOffset()：从内嵌向量选择器读取 @ 与 offset（复制清零之前）。
        if (!(node.vectorSelector() instanceof VectorSelector vec)) {
            throw new IllegalArgumentException(
                    "matrix selector 内嵌的不是 VectorSelector: " + node.vectorSelector().getClass().getName());
        }
        String offset = offsetStr(vec.originalOffsetExpr(), vec.originalOffset());
        String at = atStr(vec.timestamp(), vec.startOrEnd());
        boolean anchored = vec.anchored();
        boolean smoothed = vec.smoothed();
        // 复制并清零 @/offset/anchored/smoothed，避免打印两次（记录不可变 → 新建）。
        VectorSelector cleared = new VectorSelector(vec.name(), 0L, null, null, StartOrEnd.NONE,
                vec.labelMatchers(), false, false, vec.posRange());
        String extendedAttribute = "";
        if (anchored) {
            extendedAttribute = " anchored";
        } else if (smoothed) {
            extendedAttribute = " smoothed";
        }
        String rangeStr = DurationFormat.format(node.range());
        if (node.rangeExpr() != null) {
            rangeStr = node.rangeExpr().toPromql();
        }
        return vectorSelector(cleared) + "[" + rangeStr + "]" + extendedAttribute + at + offset;
    }

    private static String subqueryExpr(SubqueryExpr node) {
        return node.expr().toPromql() + getSubqueryTimeSuffix(node);
    }

    /**
     * {@code [<range>:<step>] @ <ts> offset <offset>} 后缀。
     */
    private static String getSubqueryTimeSuffix(SubqueryExpr node) {
        String step = "";
        if (node.step() != 0) {
            step = DurationFormat.format(node.step());
        } else if (node.stepExpr() != null) {
            step = node.stepExpr().toPromql();
        }
        String offset = offsetStr(node.originalOffsetExpr(), node.originalOffset());
        String at = atStr(node.timestamp(), node.startOrEnd());
        String rangeStr = DurationFormat.format(node.range());
        if (node.rangeExpr() != null) {
            rangeStr = node.rangeExpr().toPromql();
        }
        return "[" + rangeStr + ":" + step + "]" + at + offset;
    }

    // ------------------------------------------------------------------
    // 字面量 / Paren / Unary
    // ------------------------------------------------------------------

    private static String numberLiteral(NumberLiteral node) {
        if (node.duration()) {
            if (node.val() < 0) {
                return "-" + DurationFormat.format((long) (-node.val() * 1e9));
            }
            return DurationFormat.format((long) (node.val() * 1e9));
        }
        return GoFloat.formatFloatF(node.val());
    }

    private static String parenExpr(ParenExpr node) {
        return "(" + node.expr().toPromql() + ")";
    }

    private static String stringLiteral(StringLiteral node) {
        return GoStrings.quote(node.val());
    }

    private static String unaryExpr(UnaryExpr node) {
        return node.op().symbol() + node.expr().toPromql();
    }

    // ------------------------------------------------------------------
    // VectorSelector 与共享的 offset/@ 渲染
    // ------------------------------------------------------------------

    private static String vectorSelector(VectorSelector node) {
        List<String> labelStrings = new ArrayList<>(node.labelMatchers().size());
        for (LabelMatcher matcher : node.labelMatchers()) {
            // 仅当 __name__ 匹配器是"等于且等于指标名"时跳过；显式空名匹配器不跳过。
            if (Labels.METRIC_NAME.equals(matcher.name())
                    && matcher.type() == MatchType.EQUAL
                    && matcher.value().equals(node.name())
                    && !matcher.value().isEmpty()) {
                continue;
            }
            labelStrings.add(matcher.toPromql());
        }
        StringBuilder b = new StringBuilder(64);
        b.append(node.name());
        if (!labelStrings.isEmpty()) {
            b.append('{');
            Collections.sort(labelStrings);
            writeStringsJoin(b, labelStrings, ",");
            b.append('}');
        }
        // 顺序与 Go 一致：@ → anchored/smoothed → offset。
        if (node.timestamp() != null) {
            b.append(String.format(Locale.ROOT, " @ %.3f", node.timestamp() / 1000.0));
        } else if (node.startOrEnd() == StartOrEnd.START) {
            b.append(" @ start()");
        } else if (node.startOrEnd() == StartOrEnd.END) {
            b.append(" @ end()");
        }
        if (node.anchored()) {
            b.append(" anchored");
        } else if (node.smoothed()) {
            b.append(" smoothed");
        }
        b.append(offsetStr(node.originalOffsetExpr(), node.originalOffset()));
        return b.toString();
    }

    /**
     * 对应 Go {@code writeStringsJoin}（VectorSelector 用 ","、无空格）。
     */
    private static void writeStringsJoin(StringBuilder b, List<String> elems, String sep) {
        if (elems.isEmpty()) {
            return;
        }
        b.append(elems.get(0));
        for (int i = 1; i < elems.size(); i++) {
            b.append(sep).append(elems.get(i));
        }
    }

    /**
     * Go 三分支 offset 渲染（VectorSelector / MatrixSelector.atOffset /
     * SubqueryExpr.getSubqueryTimeSuffix 共用）：表达式 &gt; 正数 &gt; 负数。
     */
    private static String offsetStr(DurationExpr offsetExpr, long originalOffset) {
        if (offsetExpr != null) {
            return " offset " + offsetExpr.toPromql();
        }
        if (originalOffset > 0) {
            return " offset " + DurationFormat.format(originalOffset);
        }
        if (originalOffset < 0) {
            return " offset -" + DurationFormat.format(-originalOffset);
        }
        return "";
    }

    /**
     * {@code @ <ts>}（%.3f 秒）/ {@code @ start()} / {@code @ end()} 三分支。
     */
    private static String atStr(Long timestamp, StartOrEnd startOrEnd) {
        if (timestamp != null) {
            return String.format(Locale.ROOT, " @ %.3f", timestamp / 1000.0);
        }
        if (startOrEnd == StartOrEnd.START) {
            return " @ start()";
        }
        if (startOrEnd == StartOrEnd.END) {
            return " @ end()";
        }
        return "";
    }
}

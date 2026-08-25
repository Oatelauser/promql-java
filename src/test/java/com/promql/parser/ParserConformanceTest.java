package com.promql.parser;

import com.promql.ast.Expr;
import com.promql.printer.Printer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Conformance 测试：移植自 Prometheus 官方 {@code promql/parser/parse_test.go}
 * 的 {@code testExpr} 主表（由 extract_cases.py 从 Go 源提取为
 * {@code parse_test_cases.tsv}，共 352 条；另有 2 条 Go 字节级无效 UTF-8
 * 用例在提取时剔除——Java UTF-16 字符串无法表达，见 PORTING.md）。
 *
 * <p>与 Go 版 TestParseExpressions 的对应关系：
 * <ul>
 *   <li>成功用例：Go 逐字段深比较 expected AST；本版（Q12/Q4 口径）改为
 *       “解析成功 + 打印幂等”（print(parse(print(ast))) == print(ast)），
 *       另有 {@link ParserAstConformanceTest} 抽样逐字段比对 AST。</li>
 *   <li>失败用例：Go 比较完整错误列表；本版中止于首个语法错误（PORTING.md
 *       已声明），故只比较<b>第一条</b>错误的起止位置与消息。</li>
 *   <li>选项：与 Go 一致，全部实验开关打开。</li>
 * </ul>
 */
@DisplayName("官方 parse_test.go 352 条一致性")
class ParserConformanceTest {

    /** 与 Go TestParseExpressions 的 optsParser 一致：全部实验开关打开。 */
    private static final ParserOptions OPTS = new ParserOptions(true, true, true, true);

    private record Case(String input, boolean fail, int errStart, int errEnd, String errMsg) {
    }

    private static List<Case> load() {
        String data;
        try (InputStream in = ParserConformanceTest.class
                .getResourceAsStream("/parse_test_cases.tsv")) {
            assertNotNull(in, "missing /parse_test_cases.tsv");
            data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Case> cases = new ArrayList<>();
        for (String line : data.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            cases.add(new Case(unescape(f[4]), "1".equals(f[0]),
                    f[1].isEmpty() ? 0 : Integer.parseInt(f[1]),
                    f[2].isEmpty() ? 0 : Integer.parseInt(f[2]),
                    unescape(f[3])));
        }
        return cases;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @TestFactory
    @DisplayName("全量用例：解析成败 + 首错位置与消息 + 打印幂等")
    List<DynamicTest> allCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : load()) {
            tests.add(DynamicTest.dynamicTest(label(c), () -> run(c)));
        }
        return tests;
    }

    private static String label(Case c) {
        String in = c.input().replace("\n", "\\n").replace("\r", "\\r");
        return (c.fail() ? "失败用例: " : "通过用例: ") + in;
    }

    private void run(Case c) {
        if (!c.fail()) {
            Expr ast;
            try {
                ast = Parser.parseExpr(c.input(), OPTS);
            } catch (RuntimeException e) {
                fail("unexpected exception on success input <" + c.input() + ">: " + e.getMessage());
                return;
            }
            String printed = Printer.toPromql(ast);
            Expr reparsed = Parser.parseExpr(printed, OPTS);
            String printed2 = Printer.toPromql(reparsed);
            if (!printed.equals(printed2)) {
                fail("print not idempotent on input <" + c.input() + ">: <"
                        + printed + "> vs <" + printed2 + ">");
            }
            return;
        }
        PromqlParseException ex = assertThrows(PromqlParseException.class,
                () -> Parser.parseExpr(c.input(), OPTS), "expected failure on <" + c.input() + ">");
        List<ParseError> errs = ex.errors();
        assertTrue(!errs.isEmpty());
        ParseError first = errs.get(0);
        if (first.positionRange().start() != c.errStart() || first.positionRange().end() != c.errEnd()) {
            fail("position mismatch on <" + c.input() + ">: expected "
                    + c.errStart() + ":" + c.errEnd() + ", got "
                    + first.positionRange().start() + ":" + first.positionRange().end()
                    + " (" + first.message() + ")");
        }
        assertEquals(c.errMsg(), first.message(), "message mismatch on <" + c.input() + ">");
    }
}

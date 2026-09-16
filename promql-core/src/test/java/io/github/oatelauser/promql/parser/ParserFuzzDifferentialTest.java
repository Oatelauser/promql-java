package io.github.oatelauser.promql.parser;

import io.github.oatelauser.promql.ast.Expr;
import io.github.oatelauser.promql.printer.Printer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 差分模糊测试：与上游 Go parser 逐条比对（非抽样）。
 *
 * <p>语料由 {@code scripts/fuzz-oracle}（Go oracle，与 docs/promql 快照
 * 同一 commit）生成——官方 352 条一致性用例的确定性突变 + 文法随机游走
 * + 病态深度探针，{@code fuzz_diff_cases.tsv} 记录 Go 对每条输入的
 * 判定：<b>成功行的打印输出（{@code expr.String()}）</b>与
 * <b>失败行的首错位置与消息</b>。本测试全量重放：
 * <ul>
 *   <li>ok 行：Java 解析须成功，且 {@link Printer#toPromql} 输出与 Go
 *       逐字相等（解析 + 打印联合差分）；</li>
 *   <li>fail 行：Java 须抛 {@link PromqlParseException}，首错起止位置与
 *       消息与 Go 逐字相等（语料强制 ASCII，字节偏移 == UTF-16 索引，
 *       PORTING.md 分歧 2 不触发）。</li>
 * </ul>
 * 资源缺失（未生成）时整体跳过；再生成命令见 PORTING.md §4。
 */
@DisplayName("差分模糊：Go oracle 语料全量重放")
class ParserFuzzDifferentialTest {

    private static final ParserOptions OPTS = new ParserOptions(true, true, true, true);

    private record Case(String input, boolean ok, int errStart, int errEnd, String errMsg, String printed) {
    }

    private static List<Case> load() {
        InputStream in = ParserFuzzDifferentialTest.class
                .getResourceAsStream("/fuzz_diff_cases.tsv");
        Assumptions.assumeTrue(in != null, "fuzz_diff_cases.tsv 未生成（scripts/fuzz-oracle），跳过差分重放");
        String data;
        try {
            data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Case> cases = new ArrayList<>();
        for (String line : data.split("\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 6) {
                continue;
            }
            cases.add(new Case(unescape(f[4]), "1".equals(f[0]),
                    f[1].isEmpty() ? 0 : Integer.parseInt(f[1]),
                    f[2].isEmpty() ? 0 : Integer.parseInt(f[2]),
                    unescape(f[3]), unescape(f[5])));
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

    @Test
    @DisplayName("4935 条：ok 行打印逐字相等 / fail 行首错位置+消息逐字相等")
    void replayAllAgainstGoOracle() {
        List<Case> cases = load();
        assertTrue(cases.size() >= 4000, "语料规模异常: " + cases.size());
        // 排查时可放大：-Dfuzz.report.max=200
        int reportMax = Integer.getInteger("fuzz.report.max", 10);
        int mismatches = 0;
        StringBuilder report = new StringBuilder();
        for (int idx = 0; idx < cases.size(); idx++) {
            Case c = cases.get(idx);
            String diff = c.ok() ? replayOk(c) : replayFail(c);
            if (diff != null) {
                mismatches++;
                if (mismatches <= reportMax) {
                    report.append("\n[").append(idx).append("] <").append(c.input()
                            .replace("\n", "\\n")).append(">\n  ").append(diff);
                }
            }
        }
        if (mismatches > 0) {
            fail("与 Go oracle 分歧 " + mismatches + "/" + cases.size() + " 条（前 " + reportMax + " 条）:"
                    + report + (mismatches > reportMax ? "\n  …" : ""));
        }
    }

    /** @return null = 一致；非 null = 分歧描述 */
    private static String replayOk(Case c) {
        Expr ast;
        try {
            ast = Parser.parseExpr(c.input(), OPTS);
        } catch (RuntimeException e) {
            return "Go 解析成功，Java 抛异常: " + e.getMessage();
        }
        String printed = Printer.toPromql(ast);
        if (!printed.equals(c.printed())) {
            return "打印输出不等:\n    Go  : " + c.printed() + "\n    Java: " + printed;
        }
        return null;
    }

    /** @return null = 一致；非 null = 分歧描述 */
    private static String replayFail(Case c) {
        PromqlParseException ex;
        try {
            Expr ast = Parser.parseExpr(c.input(), OPTS);
            return "Go 解析失败（" + c.errStart() + ":" + c.errEnd() + " " + c.errMsg()
                    + "），Java 解析成功: " + Printer.toPromql(ast);
        } catch (PromqlParseException e) {
            ex = e;
        }
        List<ParseError> errs = ex.errors();
        if (errs.isEmpty()) {
            return "Java 异常无错误明细";
        }
        ParseError first = errs.get(0);
        if (first.positionRange().start() != c.errStart() || first.positionRange().end() != c.errEnd()) {
            return "首错位置不等: Go " + c.errStart() + ":" + c.errEnd()
                    + " vs Java " + first.positionRange().start() + ":" + first.positionRange().end()
                    + " (" + first.message() + ")";
        }
        if (!c.errMsg().equals(first.message())) {
            // 正则方言容差（PORTING.md 分歧 6）：RE2 与 java.util.regex 的
            // 错误文本不同（如 “missing argument to repetition operator: `*`”
            // vs “Dangling meta character '*'”），前缀一致即视为同类。
            if (c.errMsg().startsWith("error parsing regexp:")
                    && first.message().startsWith("error parsing regexp:")) {
                return null;
            }
            return "首错消息不等:\n    Go  : " + c.errMsg() + "\n    Java: " + first.message();
        }
        return null;
    }
}

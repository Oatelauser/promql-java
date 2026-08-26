// 差分模糊语料生成器（Go oracle）。
//
// 语料 = 官方 parse_test_cases.tsv 输入的确定性突变 + 文法随机游走合成
// 表达式，逐条用上游 parser（与 docs/promql 快照同一 commit，见 go.mod
// 伪版本）解析，把「Go 的解析结果」写成 Java 差分测试的直接输入：
//
//	fuzz_diff_cases.tsv 列（制表符分隔，\\ \t \n \r 转义，与
//	parse_test_cases.tsv 同约定）：
//	  ok(1/0)  errStart  errEnd  errMsg  input  printed(仅 ok 行，Go expr.String())
//
// 失败行只取首错（Go ParseErrors.Error() 也只暴露首错，与 Java 侧
// 首错中止口径一致）。语料强制 ASCII：突变/游走只产 ASCII，非 ASCII
// 种子剔除——保证 Go 字节偏移 == Java UTF-16 索引（PORTING.md 分歧 2）。
//
// 用法：cd scripts/fuzz-oracle && go run .   （再生成需人工触发并提交产物）
package main

import (
	"bufio"
	"flag"
	"fmt"
	"math/rand"
	"os"
	"strings"

	"github.com/prometheus/prometheus/promql/parser"
)

func main() {
	seedsPath := flag.String("seeds", "../../promql-core/src/test/resources/parse_test_cases.tsv", "官方一致性用例 TSV（种子）")
	outPath := flag.String("out", "../../promql-core/src/test/resources/fuzz_diff_cases.tsv", "输出 TSV")
	perSeed := flag.Int("mutants", 12, "每个种子的突变条数")
	walkN := flag.Int("walk", 700, "文法随机游走条数")
	seed := flag.Int64("seed", 20260826, "随机种子")
	flag.Parse()

	rng := rand.New(rand.NewSource(*seed))
	seeds, skipped := loadSeeds(*seedsPath)
	inputs := make([]string, 0, len(seeds)**perSeed+*walkN+64)
	for _, s := range seeds {
		for i := 0; i < *perSeed; i++ {
			inputs = append(inputs, mutate(rng, s))
		}
	}
	for i := 0; i < *walkN; i++ {
		inputs = append(inputs, walkExpr(rng, 0))
	}
	inputs = append(inputs, structuredCases()...)

	okCount := 0
	f, err := os.Create(*outPath)
	if err != nil {
		panic(err)
	}
	w := bufio.NewWriter(f)
	fmt.Fprintf(w, "# fuzz differential corpus — oracle github.com/prometheus/prometheus@%s (== docs/promql snapshot)\n", parserVersion)
	fmt.Fprintf(w, "# seed=%d mutants/seed=%d walk=%d seeds=%d(ascii; skipped_non_ascii=%d) total=%d\n",
		*seed, *perSeed, *walkN, len(seeds), skipped, len(inputs))
	fmt.Fprintf(w, "# columns: ok errStart errEnd errMsg input printed\n")

	p := parser.NewParser(parser.Options{
		EnableExperimentalFunctions:  true,
		ExperimentalDurationExpr:     true,
		EnableExtendedRangeSelectors: true,
		EnableBinopFillModifiers:     true,
	})
	for _, in := range inputs {
		expr, err := p.ParseExpr(in)
		if err != nil {
			start, end, msg := 0, 0, ""
			if errs, ok := err.(parser.ParseErrors); ok && len(errs) > 0 {
				start, end = int(errs[0].PositionRange.Start), int(errs[0].PositionRange.End)
				msg = errs[0].Err.Error()
			} else {
				msg = err.Error()
			}
			fmt.Fprintf(w, "0\t%d\t%d\t%s\t%s\t\n", start, end, esc(msg), esc(in))
			continue
		}
		okCount++
		fmt.Fprintf(w, "1\t\t\t\t%s\t%s\n", esc(in), esc(expr.String()))
	}
	if err := w.Flush(); err != nil {
		panic(err)
	}
	if err := f.Close(); err != nil {
		panic(err)
	}
	fmt.Fprintf(os.Stderr, "seeds=%d skipped_non_ascii=%d total=%d ok=%d fail=%d -> %s\n",
		len(seeds), skipped, len(inputs), okCount, len(inputs)-okCount, *outPath)
}

// parserVersion 与 go.mod 保持一致；手写以避免为取版本号引入 buildinfo 依赖。
const parserVersion = "v0.314.0-rc.0.0.20260825140114-439e705d3585"

// ══════════ 种子加载 ══════════

func loadSeeds(path string) (seeds []string, skippedNonASCII int) {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		f := strings.Split(line, "\t")
		if len(f) < 5 {
			continue
		}
		in := unescape(f[4])
		if !isASCII(in) {
			skippedNonASCII++
			continue
		}
		seeds = append(seeds, in)
	}
	return seeds, skippedNonASCII
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] > 127 {
			return false
		}
	}
	return true
}

// ══════════ 突变 ══════════

var mutChars = []rune("{}[]()\"'`=~,+-*/%^<>!:.@ 0123456789mhsyaeoilnfstr")

var mutTokens = []string{
	"5m", "1h30m", "[5m]", "[5m:1m]", ":1m:", "@", "@ start()", "@ end()",
	"offset 5m", "offset -5m", "by (job)", "without (le)", "on (job)",
	"ignoring (le)", "group_left (le)", "group_right", "bool", " and ",
	" or ", " unless ", " Inf", "-Inf", "NaN", "0x1F", "0b101", "0o17",
	"1e3", "1.5e-3", "\\", "\"", "'", "`", "{a=\"b\"}", "a=\"b\"",
	"a=~\"b|c\"", "!= ", "{__name__=\"up\"}", "start()", "end()", "atan2",
	"limitk", "quantile", "smoothed", "anchored", "fill(0)", "fill(Inf)",
	"sum", "rate", "count_values(\"l\", x)", "label_replace",
	"holt_winters", "predict_linear", "# c", "\n", "keep_common", "keepmetrics",
	"drop_common", "dropmetrics", "start", "end", "atanh", "mad_over_time",
	"sort_by_label", "info(", "double_exponential_smoothing", "limitk(3,",
}

func mutate(rng *rand.Rand, s string) string {
	if len(s) < 2 {
		return s
	}
	rs := []rune(s) // 种子已保证 ASCII
	i := rng.Intn(len(rs))
	ins := func() string {
		if rng.Intn(10) == 0 {
			return mutTokens[rng.Intn(len(mutTokens))]
		}
		return string(mutChars[rng.Intn(len(mutChars))])
	}
	switch rng.Intn(8) {
	case 0: // 删 1 字符
		return string(append(rs[:i:i], rs[i+1:]...))
	case 1: // 插入
		return string(rs[:i]) + ins() + string(rs[i:])
	case 2: // 替换
		rs[i] = mutChars[rng.Intn(len(mutChars))]
		return string(rs)
	case 3: // 相邻交换
		if i == len(rs)-1 {
			i--
		}
		rs[i], rs[i+1] = rs[i+1], rs[i]
		return string(rs)
	case 4: // 截断
		if len(rs) < 5 {
			return string(rs)
		}
		return string(rs[:rng.Intn(len(rs)-3)+3])
	case 5: // 复制片段就近重复
		if len(rs) > 160 {
			return string(rs)
		}
		j := i + 1 + rng.Intn(min(12, len(rs)-i))
		return string(rs[:j]) + string(rs[i:j]) + string(rs[j:])
	case 6: // 尾部追加 token
		return s + ins()
	default: // 双重突变
		return mutate(rng, mutate(rng, s))
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// ══════════ 文法随机游走 ══════════

const maxDepth = 5

var (
	aggregators    = []string{"sum", "avg", "count", "min", "max", "stddev", "stdvar", "group"}
	paramAggs      = []string{"topk", "bottomk", "quantile", "limitk", "limit_ratio"} // (num, expr)
	instantFuncs   = []string{"abs", "ceil", "floor", "exp", "ln", "log2", "log10", "sqrt", "sgn", "sort", "sort_desc", "rad", "deg"}
	rangeFuncs     = []string{"rate", "irate", "increase", "delta", "idelta", "resets", "changes", "deriv", "avg_over_time", "max_over_time", "min_over_time", "sum_over_time", "count_over_time", "stddev_over_time", "mad_over_time", "last_over_time", "present_over_time"}
	binOps         = []string{"+", "-", "*", "/", "%", "^", "==", "!=", ">", "<", ">=", "<=", "and", "or", "unless", "atan2"}
	labelNames     = []string{"job", "le", "instance", "__name__", "a.b", "0"}
	metricNames    = []string{"up", "http_requests_total", "foo_bar", "process_start_time_seconds", "x"}
	matchOps       = []string{"=", "!=", "=~", "!~"}
	stringVals     = []string{"api", "x|y", "", ".*", "a\\db", "$1", "cafe"}
	durationUnits  = []string{"ms", "s", "m", "h", "d", "w", "y"}
	groupingLabels = []string{"job", "le", "instance", "a", "a.b"}
)

func walkExpr(rng *rand.Rand, d int) string {
	r := rng.Intn(100)
	switch {
	case d >= maxDepth || r < 14:
		switch rng.Intn(3) {
		case 0:
			return walkSelector(rng)
		case 1:
			return walkNumber(rng)
		default:
			return "(" + walkExpr(rng, d+1) + ")"
		}
	case r < 34: // 二元运算（可带 vector matching / bool / fill）
		s := walkExpr(rng, d+1) + " " + binOps[rng.Intn(len(binOps))] + " "
		if rng.Intn(8) == 0 {
			s += "fill(" + walkNumber(rng) + ") "
		}
		s += walkExpr(rng, d+1)
		switch rng.Intn(6) {
		case 0:
			s += " on (" + pick(rng, groupingLabels) + ")"
		case 1:
			s += " ignoring (" + pick(rng, groupingLabels) + ")"
		case 2:
			s += " on (" + pick(rng, groupingLabels) + ") group_left"
		case 3:
			s += " ignoring (" + pick(rng, groupingLabels) + ") group_right (le)"
		}
		return s
	case r < 52: // 聚合
		if rng.Intn(5) == 0 {
			return pick(rng, paramAggs) + "(" + walkNumber(rng) + ", " + walkExpr(rng, d+1) + ")"
		}
		if rng.Intn(6) == 0 {
			return "count_values(\"l\", " + walkExpr(rng, d+1) + ")"
		}
		pre := pick(rng, aggregators)
		switch rng.Intn(3) {
		case 0:
			pre += " by (" + pick(rng, groupingLabels) + ")"
		case 1:
			pre += " without (" + pick(rng, groupingLabels) + ")"
		}
		return pre + " (" + walkExpr(rng, d+1) + ")"
	case r < 72: // 函数
		switch rng.Intn(6) {
		case 0:
			return pick(rng, rangeFuncs) + "(" + walkSelector(rng) + "[" + walkDuration(rng) + "])"
		case 1:
			return pick(rng, instantFuncs) + "(" + walkExpr(rng, d+1) + ")"
		case 2:
			return "histogram_quantile(" + walkNumber(rng) + ", " + walkExpr(rng, d+1) + ")"
		case 3:
			return "label_replace(" + walkExpr(rng, d+1) + ", \"l\", \"$1\", \"v\", \"(.*)\")"
		case 4:
			return "clamp(" + walkExpr(rng, d+1) + ", " + walkNumber(rng) + ", " + walkNumber(rng) + ")"
		default:
			return pick(rng, []string{"time", "pi"}) + "()"
		}
	case r < 82: // 子查询 / 修饰符
		s := walkSelector(rng)
		switch rng.Intn(4) {
		case 0:
			s += "[" + walkDuration(rng) + ":" + walkDuration(rng) + "]"
		case 1:
			s += "[" + walkDuration(rng) + ":" + walkDuration(rng) + "] offset " + walkDuration(rng)
		case 2:
			s += "[" + walkDuration(rng) + "]"
			if rng.Intn(2) == 0 {
				s += " offset " + walkDuration(rng)
			}
		case 3:
			s += " @ " + walkNumber(rng)
		}
		return s
	default: // 一元 / 嵌套
		switch rng.Intn(3) {
		case 0:
			return "-" + walkExpr(rng, d+1)
		case 1:
			return "sum(rate(" + walkSelector(rng) + "[" + walkDuration(rng) + "])) by (" + pick(rng, groupingLabels) + ")"
		default:
			return walkExpr(rng, d+1) + " " + pick(rng, []string{"+", "*", "and"}) + " " + walkExpr(rng, d+1)
		}
	}
}

func walkSelector(rng *rand.Rand) string {
	s := pick(rng, metricNames)
	if rng.Intn(3) != 0 {
		var ms []string
		for n := rng.Intn(3) + 1; n > 0; n-- {
			name := pick(rng, labelNames)
			if rng.Intn(6) == 0 {
				name = "\"" + pick(rng, labelNames) + "\""
			}
			ms = append(ms, name+pick(rng, matchOps)+"\""+pick(rng, stringVals)+"\"")
		}
		s += "{" + strings.Join(ms, ",") + "}"
	}
	return s
}

func walkNumber(rng *rand.Rand) string {
	switch rng.Intn(8) {
	case 0:
		return fmt.Sprintf("%d", rng.Intn(10000))
	case 1:
		return fmt.Sprintf("%d.%d", rng.Intn(1000), rng.Intn(1000))
	case 2:
		return fmt.Sprintf("%de%d", rng.Intn(100), rng.Intn(21)-10)
	case 3:
		return "0x" + fmt.Sprintf("%x", rng.Intn(0xFFFF))
	case 4:
		return pick(rng, []string{"Inf", "+Inf", "-Inf", "NaN"})
	case 5:
		return fmt.Sprintf("0.%d", rng.Intn(1000))
	case 6:
		return "-" + fmt.Sprintf("%d", rng.Intn(100))
	default:
		return fmt.Sprintf("%d.%de-%d", rng.Intn(10), rng.Intn(100000), rng.Intn(9))
	}
}

func walkDuration(rng *rand.Rand) string {
	if rng.Intn(10) == 0 { // 扩展区间选择器 / 时长表达式（实验开关已开）
		return fmt.Sprintf("(%d%s + %d%s)", rng.Intn(5)+1, pick(rng, durationUnits), rng.Intn(3), pick(rng, durationUnits))
	}
	if rng.Intn(12) == 0 {
		return fmt.Sprintf("%d.%d%s", rng.Intn(3), rng.Intn(9)+1, pick(rng, durationUnits))
	}
	return fmt.Sprintf("%d%s", rng.Intn(30)+1, pick(rng, durationUnits))
}

func pick[T any](rng *rand.Rand, xs []T) T { return xs[rng.Intn(len(xs))] }

// structuredCases：确定性的深度/形态探针（游走覆盖不到的病态结构）。
func structuredCases() []string {
	var out []string
	p := "up"
	for i := 0; i < 8; i++ { // 括号嵌套
		p = "(" + p + ")"
		out = append(out, p)
	}
	q := "up"
	for i := 0; i < 10; i++ { // 二元链
		q = "(" + q + " + " + q + ")"
		if i%3 == 0 {
			out = append(out, q)
		}
	}
	sq := "up"
	for i := 0; i < 4; i++ { // 子查询嵌套
		sq = sq + "[1m:30s]"
		out = append(out, sq)
	}
	d := "5m"
	for i := 0; i < 5; i++ { // 时长表达式嵌套
		d = "(" + d + " + 1m)"
		out = append(out, "up offset "+d, "rate(up["+d+"])")
	}
	out = append(out,
		"up offset 5m @ 100", "up @ start() @ end()", "rate(up[5m]) offset -3m",
		"foo + fill(1) bar", "foo + fill(Inf) on (job) bar",
		"topk(5, up)", "count_values(\"x\", up) by (job)",
		"sum by () (up)", "sum without () (up)",
		"{__name__=\"up\", job=~\"a|b\"} @ 1e3",
		"rate(up[5m])[1h:5m] @ end()",
		"label_replace(up, \"l\", \"$1\", \"v\", \"(.*)\")",
		"0x1p-2", "0b1.01p2", "1_000", "1.5e", "0o17", "5mx", "[5m]up",
		"sum(up) by (a.b)", "{\"a.b\"=~\"x\"} offset 1y",
	)
	return out
}

// ══════════ TSV 转义（与 parse_test_cases.tsv 同约定）══════════

func esc(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '\t':
			b.WriteString(`\t`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

func unescape(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+1 < len(s) {
			i++
			switch s[i] {
			case 't':
				b.WriteByte('\t')
			case 'n':
				b.WriteByte('\n')
			case 'r':
				b.WriteByte('\r')
			default:
				b.WriteByte(s[i])
			}
		} else {
			b.WriteByte(s[i])
		}
	}
	return b.String()
}

// gen_gofloat_vectors.go — B11：GoFloat 黄金向量生成器（Go 真 oracle）。
//
// 用法（仓库根目录执行）：
//   go run scripts/gen_gofloat_vectors.go
//   go run scripts/gen_gofloat_vectors.go -n 50000 -out /tmp/gofloat_vectors.tsv
//
// 输出 TSV，三列（tab 分隔）：
//   IEEE754 位模式(16 hex) \t strconv.FormatFloat(v,'f',-1,64) \t strconv.FormatFloat(v,'g',-1,64)
// 固定随机种子（20260825）+ 固定边界表 → 输出逐字节确定，可重复生成。
// 消费方：promql-core 的 GoFloatVectorTest（资源缺失时该测试自动跳过）。
// 向量集 = 边界值表（'g' 阈值两侧、最值、次正规数、±Inf/NaN/-0）+ 全位模式
// 随机抽样，用于守护 GoFloat 手工推导的 'f'/'g' 渲染逻辑不回归。
package main

import (
	"bufio"
	"flag"
	"fmt"
	"math"
	"math/rand"
	"os"
	"runtime"
	"strconv"
)

func main() {
	out := flag.String("out", "promql-core/src/test/resources/gofloat_vectors.tsv", "输出 TSV 路径（相对 CWD）")
	n := flag.Int("n", 10000, "随机向量条数（边界值另计）")
	flag.Parse()

	f, err := os.Create(*out)
	if err != nil {
		fmt.Fprintln(os.Stderr, "create:", err)
		os.Exit(1)
	}
	defer f.Close()
	w := bufio.NewWriter(f)
	defer w.Flush()

	fmt.Fprintln(w, "# gofloat_vectors.tsv — Go strconv.FormatFloat 黄金向量（B11）")
	fmt.Fprintf(w, "# 生成：go run scripts/gen_gofloat_vectors.go（%s）；种子固定，输出确定\n", runtime.Version())
	fmt.Fprintln(w, "# 列：IEEE754位模式(16hex) \\t FormatFloat(v,'f',-1,64) \\t FormatFloat(v,'g',-1,64)")

	for _, v := range boundary() {
		emit(w, v)
	}
	r := rand.New(rand.NewSource(20260825))
	for i := 0; i < *n; i++ {
		v := math.Float64frombits(r.Uint64())
		if math.IsNaN(v) || math.IsInf(v, 0) {
			i--
			continue
		}
		emit(w, v)
	}
	fmt.Printf("wrote %s\n", *out)
}

func emit(w *bufio.Writer, v float64) {
	fmt.Fprintf(w, "%016x\t%s\t%s\n",
		math.Float64bits(v),
		strconv.FormatFloat(v, 'f', -1, 64),
		strconv.FormatFloat(v, 'g', -1, 64))
}

// boundary 覆盖 GoFloat 渲染分支的全部判定边界。
func boundary() []float64 {
	return []float64{
		0, math.Copysign(0, -1),
		1, -1, 2, 0.5, 0.25, 0.1, 1.0 / 3, 2.0 / 3,
		10, 100, 1000, 12345, 999999, 1000000, 1000001, // 'g' 定点/科学阈值（exp 5/6）两侧
		123456.789, -123456.789,
		1e5, 1e6, 1e7, 1e15, 1e16, 1e21, 1e22, math.MaxFloat64,
		1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-10, 1e-100, 1e-300, // exp -4/-5 阈值两侧
		5e-324, math.SmallestNonzeroFloat64, 1e-320, 2.2250738585072014e-308, // 次正规/最小正规
		6.02e23, 1.602177e-19, 3.141592653589793, 2.718281828459045,
		1e21 + 1,
		math.Inf(1), math.Inf(-1), math.NaN(),
	}
}

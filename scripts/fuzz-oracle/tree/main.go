package main

import (
	"fmt"
	"os"

	"github.com/prometheus/prometheus/promql/parser"
)

func dump(n parser.Node, depth int) {
	ind := ""
	for i := 0; i < depth; i++ {
		ind += "  "
	}
	r := ""
	if e, ok := n.(parser.Expr); ok {
		r = fmt.Sprintf(" [%d:%d]", e.PositionRange().Start, e.PositionRange().End)
	}
	fmt.Printf("%s%T%s\n", ind, n, r)
	for _, c := range parser.Children(n) {
		dump(c, depth+1)
	}
}

func main() {
	p := parser.NewParser(parser.Options{true, true, true, true})
	for _, in := range os.Args[1:] {
		fmt.Printf("=== %q\n", in)
		e, err := p.ParseExpr(in)
		if err != nil {
			fmt.Printf("  ERR %v\n", err)
			continue
		}
		dump(e, 1)
	}
}

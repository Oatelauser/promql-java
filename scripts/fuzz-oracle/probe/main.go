package main

import (
	"fmt"
	"os"

	"github.com/prometheus/prometheus/promql/parser"
)

func main() {
	p := parser.NewParser(parser.Options{true, true, true, true})
	for _, in := range os.Args[1:] {
		fmt.Printf("=== %q\n", in)
		_, err := p.ParseExpr(in)
		if err == nil {
			fmt.Println("  OK")
			continue
		}
		if errs, ok := err.(parser.ParseErrors); ok {
			for i, e := range errs {
				fmt.Printf("  [%d] %v:%v %s\n", i, e.PositionRange.Start, e.PositionRange.End, e.Err.Error())
			}
		} else {
			fmt.Printf("  single: %v\n", err)
		}
	}
}

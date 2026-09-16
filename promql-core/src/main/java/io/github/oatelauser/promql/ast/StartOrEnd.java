package io.github.oatelauser.promql.ast;

/**
 * {@code @ start()} / {@code @ end()} 预处理器标记。
 *
 * <p>Go 用 {@code parser.StartOrEnd} 整型常量（0=无、START、END），
 * Java 移植为枚举。
 */
public enum StartOrEnd {
    /**
     * 无预处理器（对应 Go 常量 0）。
     */
    NONE,
    /**
     * {@code @ start()}。
     */
    START,
    /**
     * {@code @ end()}。
     */
    END
}

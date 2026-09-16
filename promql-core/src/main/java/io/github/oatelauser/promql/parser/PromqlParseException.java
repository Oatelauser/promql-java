package io.github.oatelauser.promql.parser;

import java.io.Serial;
import java.util.List;

/**
 * 解析失败异常（非受检，Q7），承载 Go {@code ParseErrors} 的全部条目。
 *
 * <p>消息取<b>第一个</b>错误的完整渲染（Go {@code ParseErrors.Error()} 同
 * 义）；需要完整错误列表时用 {@link #errors()}。
 */
public class PromqlParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final transient List<ParseError> errors;

    /**
     * 以完整错误列表构造。
     */
    public PromqlParseException(List<ParseError> errors) {
        super(errors.isEmpty() ? "error contains no error message" : errors.get(0).error());
        this.errors = List.copyOf(errors);
    }

    /**
     * 全部解析错误，保持产出顺序。
     */
    public List<ParseError> errors() {
        return errors;
    }

}

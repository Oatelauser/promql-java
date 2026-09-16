package io.github.oatelauser.promql.api.response;

/**
 * histogram 的单个 bucket 四元组：边界方案 + 下界 + 上界 + 计数。
 *
 * <p>首位整数是区间开闭方案编码（对应 Go {@code model} 包 bucket 序列化
 * 的首位，0/1 区分开边界位于左侧还是右侧）；界与计数在线格式中为字符串浮点。
 */
public record Bucket(int boundary, double lower, double upper, long count) {
}

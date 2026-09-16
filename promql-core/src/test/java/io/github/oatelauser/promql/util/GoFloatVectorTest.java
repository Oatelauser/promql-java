package io.github.oatelauser.promql.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B11：{@link GoFloat} 对照 Go {@code strconv} 的黄金向量测试。
 * 向量由 {@code scripts/gen_gofloat_vectors.go}（Go 真 oracle，固定种子）生成：
 * 边界值（'g' 科学计数法阈值两侧、次正规数、±Inf/NaN/-0）+ 全位模式随机抽样。
 * 资源未生成时整体跳过（Assumptions），不影响无 Go 工具链的环境。
 */
class GoFloatVectorTest {

    @Test
    void formatAndParseMatchGoStrconv() throws IOException {
        List<String> lines = load();
        Assumptions.assumeTrue(lines != null,
                "缺 /gofloat_vectors.tsv（仓库根目录执行: go run scripts/gen_gofloat_vectors.go）");

        int n = 0;
        for (String line : lines) {
            String[] cols = line.split("\t", -1);
            assertEquals(3, cols.length, "列数: " + line);
            double v = Double.longBitsToDouble(Long.parseUnsignedLong(cols[0], 16));

            assertEquals(cols[1], GoFloat.formatFloatF(v), "'f' bits=" + cols[0]);
            assertEquals(cols[2], GoFloat.formatFloatG(v), "'g' bits=" + cols[0]);

            // 最短表示往返：有限值经 parseFloat 解析回同一位模式
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                assertEquals(v, GoFloat.parseFloat(cols[1]), "'f' 往返 bits=" + cols[0]);
                assertEquals(v, GoFloat.parseFloat(cols[2]), "'g' 往返 bits=" + cols[0]);
            }
            n++;
        }
        assertTrue(n >= 9000, "向量数异常: " + n);
    }

    /** 读 /gofloat_vectors.tsv；无资源返回 null（触发跳过）。 */
    private static List<String> load() {
        byte[] data;
        try (InputStream in = GoFloatVectorTest.class.getResourceAsStream("/gofloat_vectors.tsv")) {
            if (in == null) {
                return null;
            }
            data = in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n", -1)) {
            line = line.stripTrailing();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }
}

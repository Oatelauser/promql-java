package io.github.oatelauser.promql.api;

import io.github.oatelauser.promql.Promql;
import io.github.oatelauser.promql.api.response.ExemplarsData;
import io.github.oatelauser.promql.api.response.FloatValue;
import io.github.oatelauser.promql.api.response.LabelNamesData;
import io.github.oatelauser.promql.api.response.LabelValuesData;
import io.github.oatelauser.promql.api.response.MatrixData;
import io.github.oatelauser.promql.api.response.PrometheusException;
import io.github.oatelauser.promql.api.response.QueryData;
import io.github.oatelauser.promql.api.response.ScalarData;
import io.github.oatelauser.promql.api.response.SeriesData;
import io.github.oatelauser.promql.api.response.StringData;
import io.github.oatelauser.promql.api.response.VectorData;
import io.github.oatelauser.promql.labels.LabelMatcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基类行为矩阵：请求构造（方法/路径/参数/时长与时间格式）、AST 静态类型
 * 分发（Q4=C）、泛型重载 fail-fast（Q12）、异常包装（Q5=A）与 baseUrl
 * 规整。传输用 FakeClient 伪造，不碰网络。
 */
class AbstractPrometheusClientTest {

    /** 伪造传输：捕获请求，回放预设响应或异常。 */
    static final class FakeClient extends AbstractPrometheusClient {
        RawRequest lastRequest;
        String body;
        int status = 200;
        IOException ioFailure;
        InterruptedException interruptFailure;

        FakeClient() {
            super("http://example.com:9090/");
        }

        @Override
        protected RawResponse send(RawRequest request) throws IOException, InterruptedException {
            this.lastRequest = request;
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (interruptFailure != null) {
                throw interruptFailure;
            }
            return new RawResponse(status, body);
        }
    }

    private static List<String> values(RawRequest r, String name) {
        return r.params().stream().filter(p -> p.name().equals(name)).map(RawRequest.Param::value).toList();
    }

    private static final String VECTOR_JSON = """
            {"status":"success","data":{"resultType":"vector","result":[
              {"metric":{"__name__":"up","job":"prometheus"},"value":[1435341451.781,"1"]}]}}""";

    private static final String MATRIX_JSON = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"__name__":"up"},"values":[[1,"1"]]}]}}""";

    private static final String SCALAR_JSON = """
            {"status":"success","data":{"resultType":"scalar","result":[1,"1"]}}""";

    private static final String STRING_JSON = """
            {"status":"success","data":{"resultType":"string","result":[1,"example"]}}""";

    // ════════ baseUrl 规整 ════════

    @Test
    void baseUrlTrailingSlashStripped() {
        FakeClient c = new FakeClient();
        assertEquals("http://example.com:9090", c.baseUrl);
    }

    @Test
    void parentQueryLayerWorksStandalone() {
        // 分层拆分：只继承父层（传输核心 + 查询端点）即可完成查询
        RawRequest[] captured = new RawRequest[1];
        AbstractPrometheusQueryClient c = new AbstractPrometheusQueryClient("http://example.com:9090/") {
            @Override
            protected RawResponse send(RawRequest request) {
                captured[0] = request;
                return new RawResponse(200, VECTOR_JSON);
            }
        };
        VectorData d = assertInstanceOf(VectorData.class, c.query(Promql.parse("up")));
        assertEquals("/api/v1/query", captured[0].path());
        assertEquals(1, d.samples().size());
    }

    // ════════ query ════════

    @Test
    void queryBuildsPostWithTimeAndTimeout() {
        FakeClient c = new FakeClient();
        c.body = VECTOR_JSON;
        VectorData d = assertInstanceOf(VectorData.class,
                c.query(Promql.parse("up"), 1435341451781L, Duration.ofSeconds(30)));

        assertEquals("POST", c.lastRequest.method());
        assertEquals("/api/v1/query", c.lastRequest.path());
        assertEquals(List.of("up"), values(c.lastRequest, "query"));
        assertEquals(List.of("1435341451.781"), values(c.lastRequest, "time"));
        assertEquals(List.of("30s"), values(c.lastRequest, "timeout"));
        assertEquals(1, d.samples().size());
    }

    @Test
    void queryOmitsNullTimeAndTimeout() {
        FakeClient c = new FakeClient();
        c.body = VECTOR_JSON;
        QueryData d = c.query(Promql.parse("up"));
        assertNull(c.lastRequest.paramValue("time"));
        assertNull(c.lastRequest.paramValue("timeout"));
    }

    @Test
    void queryDurationFormats() {
        FakeClient c = new FakeClient();
        c.body = SCALAR_JSON;
        c.query(Promql.parse("1"), 1000L, Duration.ofMillis(1500));
        assertEquals(List.of("1500ms"), values(c.lastRequest, "timeout"));
        assertEquals(List.of("1"), values(c.lastRequest, "time"));
    }

    @Test
    void queryDispatchesByStaticAstType() {
        FakeClient c = new FakeClient();

        c.body = VECTOR_JSON;
        assertInstanceOf(VectorData.class, c.query(Promql.parse("up")));
        c.body = SCALAR_JSON;
        assertInstanceOf(ScalarData.class, c.query(Promql.parse("1 + 2")));
        c.body = STRING_JSON;
        assertInstanceOf(StringData.class, c.query(Promql.parse("\"example\"")));
    }

    @Test
    void typedOverloadReturnsExactVariant() {
        FakeClient c = new FakeClient();
        c.body = SCALAR_JSON;
        ScalarData d = c.query(Promql.parse("1"), null, null, ScalarData.class);
        assertEquals(1.0, assertInstanceOf(FloatValue.class, d.value()).value());
        assertEquals("/api/v1/query", c.lastRequest.path()); // 请求已发出（成功路径）
    }

    @Test
    void typedOverloadFailsFastWithoutSending() {
        FakeClient c = new FakeClient();
        c.body = SCALAR_JSON;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.query(Promql.parse("1"), null, null, VectorData.class));
        assertTrue(e.getMessage().contains("ScalarData"));
        assertTrue(e.getMessage().contains("VectorData"));
        assertNull(c.lastRequest); // 未发请求
    }

    // ════════ queryRange ════════

    @Test
    void queryRangeBuildsPostWithRangeParams() {
        FakeClient c = new FakeClient();
        c.body = MATRIX_JSON;
        MatrixData d = c.queryRange(Promql.parse("up"), 1435341450000L, 1435341550000L,
                Duration.ofSeconds(15), Duration.ofMillis(1500));
        assertEquals("POST", c.lastRequest.method());
        assertEquals("/api/v1/query_range", c.lastRequest.path());
        assertEquals(List.of("up"), values(c.lastRequest, "query"));
        assertEquals(List.of("1435341450"), values(c.lastRequest, "start"));
        assertEquals(List.of("1435341550"), values(c.lastRequest, "end"));
        assertEquals(List.of("15s"), values(c.lastRequest, "step"));
        assertEquals(List.of("1500ms"), values(c.lastRequest, "timeout"));
        assertEquals(1, d.series().size());
    }

    @Test
    void queryRangeRejectsNullStep() {
        FakeClient c = new FakeClient();
        assertThrows(NullPointerException.class,
                () -> c.queryRange(Promql.parse("up"), 0, 1, null, null));
    }

    // ════════ 第 2 层端点 ════════

    @Test
    void seriesBuildsGetWithRepeatedMatchers() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[{\"__name__\":\"up\"}]}";
        SeriesData d = c.series(0L, 60000L, List.of("up", "process_start_time_seconds{job=\"prometheus\"}"));
        assertEquals("GET", c.lastRequest.method());
        assertEquals("/api/v1/series", c.lastRequest.path());
        assertEquals(List.of("up", "process_start_time_seconds{job=\"prometheus\"}"),
                values(c.lastRequest, "match[]"));
        assertEquals(List.of("0"), values(c.lastRequest, "start"));
        assertEquals(List.of("60"), values(c.lastRequest, "end"));
        assertEquals(1, d.series().size());
    }

    @Test
    void seriesRequiresAtLeastOneMatcher() {
        FakeClient c = new FakeClient();
        assertThrows(IllegalArgumentException.class, () -> c.series(null, null, List.of()));
        assertNull(c.lastRequest);
    }

    @Test
    void seriesRejectsInvalidMatcherFailFast() {
        FakeClient c = new FakeClient();
        c.body = "{}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.series(null, null, List.of("up", "1 + 1")));
        assertTrue(e.getMessage().contains("选择器[1]"), e.getMessage());
        assertNull(c.lastRequest); // 未发请求
    }

    @Test
    void labelNamesBuildsGetWithMatchers() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[\"job\"]}";
        LabelNamesData d = c.labelNames(null, null, List.of("up"));
        assertEquals("GET", c.lastRequest.method());
        assertEquals("/api/v1/labels", c.lastRequest.path());
        assertEquals(List.of("up"), values(c.lastRequest, "match[]"));
        assertEquals(List.of("job"), d.names());
    }

    @Test
    void labelValuesBuildsPathSegment() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[\"api\"]}";
        LabelValuesData d = c.labelValues("job", null, null, null);
        assertEquals("/api/v1/label/job/values", c.lastRequest.path());
        assertEquals("GET", c.lastRequest.method());
        assertEquals(List.of("api"), d.values());
    }

    @Test
    void queryExemplarsBuildsPost() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[]}";
        ExemplarsData d = c.queryExemplars("up", 0L, 60000L);
        assertEquals("POST", c.lastRequest.method());
        assertEquals("/api/v1/query_exemplars", c.lastRequest.path());
        assertEquals(List.of("up"), values(c.lastRequest, "query"));
        assertEquals(List.of("0"), values(c.lastRequest, "start"));
        assertEquals(0, d.groups().size());
    }

    @Test
    void queryExemplarsRejectsNonSelectorFailFast() {
        FakeClient c = new FakeClient();
        c.body = "{}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.queryExemplars("sum(up)", null, null));
        assertTrue(e.getMessage().contains("选择器"), e.getMessage());
        assertNull(c.lastRequest); // 未发请求
    }

    @Test
    void queryExemplarsTypedOverloadRendersSelector() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[]}";
        List<LabelMatcher> ms = Promql.parseMetricSelector("up{job=\"api\"}");
        ExemplarsData d = c.queryExemplars(ms, 0L, null);
        String expected = "{" + ms.stream().map(LabelMatcher::toPromql).collect(Collectors.joining(",")) + "}";
        assertEquals(List.of(expected), values(c.lastRequest, "query"));
        assertEquals(0, d.groups().size());
    }

    // ════════ RequestOptions：方法切换 + 参数透传 ════════

    @Test
    void optionsSwitchQueryToGet() {
        FakeClient c = new FakeClient();
        c.body = VECTOR_JSON;
        VectorData d = assertInstanceOf(VectorData.class,
                c.query(Promql.parse("up"), 1435341451781L, Duration.ofSeconds(30), RequestOptions.get()));
        assertEquals("GET", c.lastRequest.method()); // 缺省 POST，显式覆盖为 GET
        assertEquals(List.of("up"), values(c.lastRequest, "query"));
        assertEquals(List.of("1435341451.781"), values(c.lastRequest, "time"));
        assertEquals(1, d.samples().size());
    }

    @Test
    void optionsNullKeepsEndpointDefaults() {
        FakeClient c = new FakeClient();
        c.body = VECTOR_JSON;
        c.query(Promql.parse("up"), null, null, (RequestOptions) null);
        assertEquals("POST", c.lastRequest.method());

        c.body = "{\"status\":\"success\",\"data\":[{\"__name__\":\"up\"}]}";
        c.series(null, null, List.of("up"), null);
        assertEquals("GET", c.lastRequest.method());
    }

    @Test
    void optionsSwitchSeriesToPost() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[]}";
        c.series(0L, 60000L, List.of("up", "down"), RequestOptions.post());
        assertEquals("POST", c.lastRequest.method()); // 缺省 GET
        assertEquals(List.of("up", "down"), values(c.lastRequest, "match[]"));
    }

    @Test
    void optionsSwitchQueryRangeAndExemplarsToGet() {
        FakeClient c = new FakeClient();
        c.body = MATRIX_JSON;
        c.queryRange(Promql.parse("up"), 0, 60, Duration.ofSeconds(15), null, RequestOptions.get());
        assertEquals("GET", c.lastRequest.method());

        c.body = "{\"status\":\"success\",\"data\":[]}";
        c.queryExemplars("up", 0L, 60_000L, RequestOptions.get());
        assertEquals("GET", c.lastRequest.method());
    }

    @Test
    void optionsSwitchLabelEndpointsToPost() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[\"job\"]}";
        c.labelNames(null, null, List.of("up"), RequestOptions.post());
        assertEquals("POST", c.lastRequest.method());
        assertEquals("/api/v1/labels", c.lastRequest.path());

        c.body = "{\"status\":\"success\",\"data\":[\"api\"]}";
        c.labelValues("job", null, null, null, RequestOptions.post());
        assertEquals("POST", c.lastRequest.method());
        assertEquals("/api/v1/label/job/values", c.lastRequest.path());
    }

    @Test
    void optionsExtraParamsAppendedAfterStandardOnes() {
        FakeClient c = new FakeClient();
        c.body = VECTOR_JSON;
        c.query(Promql.parse("up"), 1000L, null,
                RequestOptions.post(List.of(new RawRequest.Param("x-custom", "1"))));
        List<RawRequest.Param> ps = c.lastRequest.params();
        // 标准参数在前、透传在后；无 options 时 params 列表即标准列表（零拷贝）
        assertEquals("query", ps.get(0).name());
        assertEquals("time", ps.get(1).name());
        assertEquals("x-custom", ps.get(ps.size() - 1).name());
        assertEquals(List.of("1"), values(c.lastRequest, "x-custom"));
    }

    @Test
    void optionsExtraParamsAllowDuplicateKeys() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[]}";
        c.series(null, null, List.of("up"),
                RequestOptions.get(List.of(new RawRequest.Param("match[]", "down"))));
        // 重复 match[] 键不合并（多值语义），透传值在标准值之后
        assertEquals(List.of("up", "down"), values(c.lastRequest, "match[]"));
    }

    @Test
    void typedQueryWithOptionsKeepsFailFastAndHonorsMethod() {
        FakeClient c = new FakeClient();
        c.body = SCALAR_JSON;
        ScalarData d = c.query(Promql.parse("1"), null, null, ScalarData.class, RequestOptions.get());
        assertEquals("GET", c.lastRequest.method());
        assertEquals(1.0, assertInstanceOf(FloatValue.class, d.value()).value());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.query(Promql.parse("1"), null, null, VectorData.class, RequestOptions.get()));
        assertTrue(e.getMessage().contains("VectorData"));
    }

    @Test
    void typedExemplarsOverloadPassesOptionsThrough() {
        FakeClient c = new FakeClient();
        c.body = "{\"status\":\"success\",\"data\":[]}";
        List<LabelMatcher> ms = Promql.parseMetricSelector("up{job=\"api\"}");
        c.queryExemplars(ms, 0L, null, RequestOptions.get());
        assertEquals("GET", c.lastRequest.method());
        assertEquals(1, values(c.lastRequest, "query").size());
    }

    @Test
    void requestOptionsRecordSemantics() {
        assertEquals(List.of(), RequestOptions.get(null).extraParams()); // null 归一为空
        assertEquals(RequestOptions.DEFAULT, RequestOptions.get());
        assertThrows(NullPointerException.class, () -> RequestOptions.withParam(null, "v"));
        RequestOptions base = RequestOptions.withParam("a", "1");
        RequestOptions merged = base.withExtra("b", "2");
        assertEquals(List.of(new RawRequest.Param("a", "1")), base.extraParams()); // 原对象不变
        assertEquals(List.of(new RawRequest.Param("a", "1"), new RawRequest.Param("b", "2")),
                merged.extraParams());
        assertFalse(merged.usePost() != base.usePost()); // 方法位随拷贝保留
    }

    // ════════ 异常包装（Q5=A）════════

    @Test
    void ioExceptionWrappedUncheckedWithContext() {
        FakeClient c = new FakeClient();
        c.ioFailure = new IOException("connection refused");
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> c.query(Promql.parse("up")));
        assertTrue(e.getMessage().contains("/api/v1/query"));
        assertEquals("connection refused", e.getCause().getMessage());
    }

    @Test
    void interruptRestoresFlagAndWrapsUncheckedIo() {
        FakeClient c = new FakeClient();
        c.interruptFailure = new InterruptedException();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> c.query(Promql.parse("up")));
        assertTrue(e.getCause() instanceof InterruptedIOException, String.valueOf(e.getCause()));
        // doSend 已恢复中断标志位；Thread.interrupted() 读取并清除，避免污染后续测试
        assertTrue(Thread.interrupted());
    }

    @Test
    void errorEnvelopeSurfacesAsPrometheusException() {
        FakeClient c = new FakeClient();
        c.status = 422;
        c.body = "{\"status\":\"error\",\"errorType\":\"execution\",\"error\":\"query failed\"}";
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> c.query(Promql.parse("up")));
        assertEquals(422, e.httpStatus());
        assertEquals("query failed", e.getMessage());
    }

    // ════════ 内部工具直测（同包可见）════════

    @Test
    void durationsFormatAndParseRoundTrip() {
        assertEquals("30s", Durations.format(Duration.ofSeconds(30)));
        assertEquals("1500ms", Durations.format(Duration.ofMillis(1500)));
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMillis(1500), Durations.parse("1500ms"));
        assertNull(Durations.parse("1m"));
        assertNull(Durations.parse("abc"));
    }

    @Test
    void apiHttpFormEncodePreservesOrderAndRepeats() {
        List<RawRequest.Param> params = List.of(
                new RawRequest.Param("match[]", "up"),
                new RawRequest.Param("query", "a b&c=d"),
                new RawRequest.Param("match[]", "down{job=\"x\"}"));
        assertEquals("match%5B%5D=up&query=a+b%26c%3Dd&match%5B%5D=down%7Bjob%3D%22x%22%7D",
                HttpUtils.formEncode(params));
    }
}

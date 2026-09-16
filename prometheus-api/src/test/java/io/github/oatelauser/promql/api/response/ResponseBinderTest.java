package io.github.oatelauser.promql.api.response;

import io.github.oatelauser.promql.labels.Label;
import io.github.oatelauser.promql.value.ValueType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResponseBinder} golden 绑定矩阵：4 种 resultType × 特殊浮点
 * （±Inf/NaN）× 转义/Unicode 标签 × native histogram × 全部 errorType ×
 * 类型不一致 × 畸形响应 × 第 2 层四端点。数据合成自 Prometheus 官方
 * HTTP API 文档示例形态。
 */
class ResponseBinderTest {

    private static final long T1 = 1435341451781L;
    private static final long T2 = 1435341452781L;

    // ════════ vector ════════

    @Test
    void vectorGolden() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"__name__":"up","job":"prometheus"},"value":[1435341451.781,"1"]},
                  {"metric":{"job":"node","__name__":"up","unicode":"日本語"},"value":[1435341451.781,"NaN"]},
                  {"metric":{"__name__":"temp"},"value":[1435341452,"+Inf"]},
                  {"metric":{"__name__":"neg"},"value":[1435341452,"-Inf"]},
                  {"metric":{"__name__":"frac"},"value":[1435341452.5,"0.125"]}
                ]}}""";
        VectorData d = (VectorData) ResponseBinder.bindQuery(json, 200, ValueType.VECTOR);
        assertEquals(5, d.samples().size());
        assertEquals(Instant.ofEpochMilli(T1), d.samples().get(0).toInstant());

        VectorSample s0 = d.samples().get(0);
        // 标签集按 name 排序（Labels.newLabels 不变式）
        assertEquals(List.of(new Label("__name__", "up"), new Label("job", "prometheus")), s0.metric());
        FloatValue v0 = assertInstanceOf(FloatValue.class, s0.value());
        assertEquals(1.0, v0.value());

        assertEquals(Double.NaN, ((FloatValue) d.samples().get(1).value()).value());
        assertEquals(List.of(new Label("__name__", "up"), new Label("job", "node"),
                new Label("unicode", "日本語")), d.samples().get(1).metric());
        assertEquals(Double.POSITIVE_INFINITY, ((FloatValue) d.samples().get(2).value()).value());
        assertEquals(Double.NEGATIVE_INFINITY, ((FloatValue) d.samples().get(3).value()).value());
        assertEquals(0.125, ((FloatValue) d.samples().get(4).value()).value());
        assertEquals(1435341452500L, d.samples().get(4).timestamp());
        assertEquals(List.of(), d.warnings());
    }

    @Test
    void vectorEmptyResult() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[]}}""";
        VectorData d = (VectorData) ResponseBinder.bindQuery(json, 200, ValueType.VECTOR);
        assertEquals(List.of(), d.samples());
    }

    @Test
    void vectorNativeHistogramUsesHistogramKey() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"__name__":"h"},"histogram":[1435341451.781,
                    {"count":"10","sum":"0.7","buckets":[
                      [3,"-0.25","-0.2","2"],
                      [0,"-0.2","0.010000000000000002","8"]
                    ]}
                  ]}
                ]}}""";
        VectorData d = (VectorData) ResponseBinder.bindQuery(json, 200, ValueType.VECTOR);
        HistogramValue hv = assertInstanceOf(HistogramValue.class, d.samples().get(0).value());
        assertEquals(10L, hv.histogram().count());
        assertEquals(0.7, hv.histogram().sum());
        assertEquals(2, hv.histogram().buckets().size());
        Bucket b0 = hv.histogram().buckets().get(0);
        assertEquals(3, b0.boundary());
        assertEquals(-0.25, b0.lower());
        assertEquals(-0.2, b0.upper());
        assertEquals(2L, b0.count());
        assertEquals(T1, d.samples().get(0).timestamp());
    }

    @Test
    void vectorEscapedLabelValue() {
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"esc\":\"a\\\"b\\\\c\"},\"value\":[1,\"1\"]}]}}";
        VectorData d = (VectorData) ResponseBinder.bindQuery(json, 200, ValueType.VECTOR);
        assertEquals(List.of(new Label("esc", "a\"b\\c")), d.samples().get(0).metric());
    }

    @Test
    void warningsCarried() {
        String json = """
                {"status":"success","warnings":["查询被截断","second"],"data":
                  {"resultType":"vector","result":[]}}""";
        VectorData d = (VectorData) ResponseBinder.bindQuery(json, 200, ValueType.VECTOR);
        assertEquals(List.of("查询被截断", "second"), d.warnings());
    }

    @Test
    void infosCarriedOnQueryAndLayer2() {
        String vector = """
                {"status":"success","infos":["PromQL info annotation"],"data":
                  {"resultType":"vector","result":[]}}""";
        VectorData d = (VectorData) ResponseBinder.bindQuery(vector, 200, ValueType.VECTOR);
        assertEquals(List.of("PromQL info annotation"), d.infos());

        String series = """
                {"status":"success","warnings":["w1"],"infos":["i1"],"data":[{"job":"api"}]}""";
        SeriesData s = ResponseBinder.bindSeries(series, 200);
        assertEquals(List.of("w1"), s.warnings());
        assertEquals(List.of("i1"), s.infos());
        assertEquals(List.of(List.of(new Label("job", "api"))), s.series());
    }

    // ════════ matrix ════════

    @Test
    void matrixGoldenWithValuesAndHistograms() {
        String json = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"__name__":"up","job":"prometheus"},
                   "values":[[1435341451.781,"1"],[1435341452.781,"0"]]},
                  {"metric":{"__name__":"nh"},
                   "values":[[1,"1"]],
                   "histograms":[[2,{"count":"4","sum":"2.5","buckets":[[0,"0.5","1","3"]]}]]}
                ]}}""";
        MatrixData d = (MatrixData) ResponseBinder.bindQuery(json, 200, ValueType.MATRIX);
        assertEquals(2, d.series().size());

        MatrixSeries s0 = d.series().get(0);
        assertEquals(2, s0.floats().size());
        assertEquals(0, s0.histograms().size());
        assertEquals(T1, s0.floats().get(0).timestamp());
        assertEquals(1.0, s0.floats().get(0).value());
        assertEquals(T2, s0.floats().get(1).timestamp());
        assertEquals(0.0, s0.floats().get(1).value());

        MatrixSeries s1 = d.series().get(1);
        assertEquals(1, s1.floats().size());
        HistogramSample hs = s1.histograms().get(0);
        assertEquals(2000L, hs.timestamp());
        assertEquals(4L, hs.histogram().count());
        assertEquals(2.5, hs.histogram().sum());
        assertEquals(1, hs.histogram().buckets().size());
    }

    // ════════ scalar / string ════════

    @Test
    void scalarGolden() {
        String json = """
                {"status":"success","data":{"resultType":"scalar","result":[1435341451.781,"1"]}}""";
        ScalarData d = (ScalarData) ResponseBinder.bindQuery(json, 200, ValueType.SCALAR);
        assertEquals(T1, d.timestamp());
        assertEquals(Instant.ofEpochMilli(T1), d.toInstant());
        assertEquals(1.0, assertInstanceOf(FloatValue.class, d.value()).value());
    }

    @Test
    void stringGolden() {
        String json = """
                {"status":"success","data":{"resultType":"string","result":[1435341451.781,"example"]}}""";
        StringData d = (StringData) ResponseBinder.bindQuery(json, 200, ValueType.STRING);
        assertEquals(T1, d.timestamp());
        assertEquals("example", d.value());
    }

    // ════════ 错误响应（全部 errorType）════════

    @Test
    void errorBadData() {
        String json = """
                {"status":"error","errorType":"bad_data",
                 "error":"invalid parameter \\"query\\": 1:5: parse error: unexpected )"}""";
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 400, ValueType.VECTOR));
        assertEquals(ErrorType.BAD_DATA, e.errorType());
        assertEquals(400, e.httpStatus());
        assertTrue(e.getMessage().contains("parse error"));
        assertEquals(json, e.rawBody());
    }

    @Test
    void errorTimeout() {
        String json = """
                {"status":"error","errorType":"timeout","error":"query timeout"}""";
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 503, ValueType.VECTOR));
        assertEquals(ErrorType.TIMEOUT, e.errorType());
        assertEquals(503, e.httpStatus());
    }

    @Test
    void errorCanceledIncludingBritishAlias() {
        for (String symbol : new String[]{"canceled", "cancelled"}) {
            String json = "{\"status\":\"error\",\"errorType\":\"" + symbol + "\",\"error\":\"x\"}";
            PrometheusException e = assertThrows(PrometheusException.class,
                    () -> ResponseBinder.bindQuery(json, 500, ValueType.VECTOR));
            assertEquals(ErrorType.CANCELED, e.errorType());
        }
    }

    @Test
    void errorExecutionAndUnavailableAndInternal() {
        assertEquals(ErrorType.EXECUTION, errorTypeOf("{\"status\":\"error\",\"errorType\":\"execution\",\"error\":\"x\"}"));
        assertEquals(ErrorType.UNAVAILABLE, errorTypeOf("{\"status\":\"error\",\"errorType\":\"unavailable\",\"error\":\"x\"}"));
        assertEquals(ErrorType.INTERNAL, errorTypeOf("{\"status\":\"error\",\"errorType\":\"internal\",\"error\":\"x\"}"));
    }

    @Test
    void errorUnknownTypeFallsBackInternal() {
        assertEquals(ErrorType.INTERNAL,
                errorTypeOf("{\"status\":\"error\",\"errorType\":\"future_kind\",\"error\":\"x\"}"));
        // 缺 errorType 字段同样归 INTERNAL
        assertEquals(ErrorType.INTERNAL, errorTypeOf("{\"status\":\"error\",\"error\":\"x\"}"));
    }

    private ErrorType errorTypeOf(String json) {
        return assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 200, ValueType.VECTOR)).errorType();
    }

    // ════════ 类型不一致（Q4=C 交叉校验）════════

    @Test
    void typeMismatchIsInternalError() {
        String json = """
                {"status":"success","data":{"resultType":"scalar","result":[1,"1"]}}""";
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 200, ValueType.VECTOR));
        assertEquals(ErrorType.INTERNAL, e.errorType());
        assertTrue(e.getMessage().contains("不一致"));
    }

    @Test
    void unknownResultTypeIsMalformed() {
        String json = """
                {"status":"success","data":{"resultType":"weird","result":[]}}""";
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 200, ValueType.VECTOR));
        assertEquals(ErrorType.INTERNAL, e.errorType());
        assertTrue(e.getMessage().contains("weird"));
    }

    // ════════ 畸形响应 ════════

    @Test
    void malformedNotJson() {
        assertMalformed("503 Service Unavailable");
    }

    @Test
    void malformedEmptyBody() {
        assertMalformed("");
    }

    @Test
    void malformedMissingData() {
        assertMalformed("{\"status\":\"success\"}");
    }

    @Test
    void malformedUnknownStatus() {
        assertMalformed("{\"status\":\"maybe\",\"data\":{}}");
    }

    @Test
    void malformedPairSize() {
        assertMalformed("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
                + "[{\"metric\":{},\"value\":[1,\"1\",\"extra\"]}]}}");
    }

    @Test
    void malformedBucketTupleSize() {
        assertMalformed("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
                + "[{\"metric\":{},\"value\":[1,{\"count\":\"1\",\"sum\":\"1\",\"buckets\":[[0,\"1\"]]}]}]}}");
    }

    @Test
    void malformedBadFloatText() {
        assertMalformed("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1,\"not-a-number\"]}}");
    }

    private void assertMalformed(String json) {
        PrometheusException e = assertThrows(PrometheusException.class,
                () -> ResponseBinder.bindQuery(json, 200, ValueType.VECTOR));
        assertEquals(ErrorType.INTERNAL, e.errorType());
        assertTrue(e.getMessage().startsWith("畸形响应"), e.getMessage());
    }

    // ════════ 第 2 层端点 ════════

    @Test
    void seriesGoldenKeepsGroupBoundaries() {
        String json = """
                {"status":"success","data":[
                  {"job":"prometheus","__name__":"up"},
                  {"__name__":"http_requests_total","job":"api","handler":"/api/v1/query"}
                ]}""";
        SeriesData d = ResponseBinder.bindSeries(json, 200);
        assertEquals(2, d.series().size());
        // 组内按 name 排序（'_' (0x5F) < 'h'/'j'）；组间保持服务器顺序
        assertEquals(List.of(new Label("__name__", "up"), new Label("job", "prometheus")), d.series().get(0));
        assertEquals(List.of(new Label("__name__", "http_requests_total"),
                new Label("handler", "/api/v1/query"), new Label("job", "api")), d.series().get(1));
        assertEquals(List.of(), d.warnings());
        assertEquals(List.of(), d.infos());
    }

    @Test
    void labelNamesGolden() {
        String json = """
                {"status":"success","data":["__name__","job","instance"]}""";
        LabelNamesData d = ResponseBinder.bindLabelNames(json, 200);
        assertEquals(List.of("__name__", "job", "instance"), d.names());
    }

    @Test
    void labelValuesGolden() {
        String json = """
                {"status":"success","data":["prometheus","node"]}""";
        LabelValuesData d = ResponseBinder.bindLabelValues(json, 200);
        assertEquals(List.of("prometheus", "node"), d.values());
    }

    @Test
    void exemplarsGolden() {
        String json = """
                {"status":"success","data":[
                  {"seriesLabels":{"__name__":"http_requests_total","job":"api"},
                   "exemplars":[
                     {"labels":{"trace_id":"Span-1234","trace_id2":"Synthetic"},"value":"1","timestamp":1637804423.153}
                   ]}
                ]}""";
        ExemplarsData d = ResponseBinder.bindExemplars(json, 200);
        assertEquals(1, d.groups().size());
        assertEquals(List.of(new Label("__name__", "http_requests_total"), new Label("job", "api")),
                d.groups().get(0).seriesLabels());
        Exemplar ex = d.groups().get(0).exemplars().get(0);
        assertEquals(1.0, ex.value());
        assertEquals(1637804423153L, ex.timestamp());
        assertEquals(List.of(new Label("trace_id", "Span-1234"), new Label("trace_id2", "Synthetic")), ex.labels());
    }
}

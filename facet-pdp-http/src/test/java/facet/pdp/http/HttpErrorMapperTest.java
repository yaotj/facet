package facet.pdp.http;

import com.fasterxml.jackson.core.JacksonException;
import facet.core.runtime.DeadlineExceededException;
import facet.core.runtime.EvalException;
import facet.core.schema.SchemaException;
import facet.core.spi.HistoryTruncatedException;
import facet.core.spi.StorageException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常到 HTTP 响应的映射。
 *
 * <p>责任链重构后，每条映射都是链上一环、可单独验证：这里把每一种异常都喂给 {@link HttpErrorMapper#map}，
 * 断言它落到正确的状态码、错误码、消息与 {@code Retry-After}。一旦哪条映射被改错（比如把 504 弄成 422、
 * 或把兜底 500 的消息泄露了内部细节），这个测试会立刻红。
 */
class HttpErrorMapperTest {

    @Test
    void bodyTooLargeMapsTo413() {
        var action = HttpErrorMapper.map(new BodyTooLarge("超过 64 字节"));

        assertEquals(413, action.status());
        assertEquals("payload_too_large", action.code());
        assertEquals("超过 64 字节", action.message());
        assertFalse(action.retryAfter());
    }

    @Test
    void malformedJsonMapsTo400() {
        // JacksonException 是接口，拿不到直接实例；用一个真正会抛它的解析触发
        var thrown = parseFailure("{not json");
        assertTrue(thrown instanceof JacksonException, thrown.getClass().getName());

        var action = HttpErrorMapper.map(thrown);

        assertEquals(400, action.status());
        assertEquals("bad_request", action.code());
        assertFalse(action.retryAfter());
    }

    @Test
    void schemaErrorMapsTo400() {
        var action = HttpErrorMapper.map(new SchemaException("关系名拼错"));

        assertEquals(400, action.status());
        assertEquals("bad_request", action.code());
        assertEquals("关系名拼错", action.message());
    }

    @Test
    void illegalArgumentMapsTo400() {
        var action = HttpErrorMapper.map(new IllegalArgumentException("缺必填字段"));

        assertEquals(400, action.status());
        assertEquals("bad_request", action.code());
        assertEquals("缺必填字段", action.message());
    }

    @Test
    void unsupportedOperationMapsTo405() {
        var action = HttpErrorMapper.map(new UnsupportedOperationException("内存适配器没有撤销能力"));

        assertEquals(405, action.status());
        assertEquals("not_supported", action.code());
    }

    @Test
    void deadlineExceededMapsTo504WithRetryAfter() {
        var action = HttpErrorMapper.map(new DeadlineExceededException("判定超过请求期限"));

        assertEquals(504, action.status());
        assertEquals("deadline_exceeded", action.code());
        assertEquals("判定超过请求期限", action.message());
        assertTrue(action.retryAfter());
    }

    @Test
    void evalFailureMapsTo422() {
        var action = HttpErrorMapper.map(new EvalException("求值失败", null));

        assertEquals(422, action.status());
        assertEquals("cannot_evaluate", action.code());
        assertEquals("求值失败", action.message());
    }

    @Test
    void historyTruncatedMapsTo409CarryingWatermark() {
        var action = HttpErrorMapper.map(
                new HistoryTruncatedException(new facet.core.ir.Revision(5), new facet.core.ir.Revision(100)));

        assertEquals(409, action.status());
        assertEquals("history_truncated", action.code());
        assertTrue(action.message().contains("100"), action.message());
    }

    @Test
    void retryableStorageMapsTo503WithRetryAfter() {
        var action = HttpErrorMapper.map(new StorageException("连接中断", null, true));

        assertEquals(503, action.status());
        assertEquals("storage_unavailable", action.code());
        assertEquals("存储暂时不可用，请重试", action.message());
        assertTrue(action.retryAfter());
    }

    @Test
    void permanentStorageMapsTo500() {
        var action = HttpErrorMapper.map(new StorageException("磁盘坏了", null, false));

        assertEquals(500, action.status());
        assertEquals("internal_error", action.code());
        // 对外脱敏：消息是固定的，不带内部细节
        assertEquals("判定失败", action.message());
    }

    @Test
    void unmappedExceptionFallsThroughTo500() {
        var action = HttpErrorMapper.map(new RuntimeException("不该泄漏的内部细节"));

        assertEquals(500, action.status());
        assertEquals("internal_error", action.code());
        assertEquals("判定失败", action.message());
        assertFalse(action.retryAfter());
    }

    private static Throwable parseFailure(String body) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            return new AssertionError("应当抛出 JacksonException");
        } catch (IOException e) {
            return e;
        }
    }
}

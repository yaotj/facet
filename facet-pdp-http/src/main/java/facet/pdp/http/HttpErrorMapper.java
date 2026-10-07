package facet.pdp.http;

import com.fasterxml.jackson.core.JacksonException;
import facet.core.runtime.DeadlineExceededException;
import facet.core.runtime.EvalException;
import facet.core.schema.SchemaException;
import facet.core.spi.HistoryTruncatedException;
import facet.core.spi.StorageException;

import java.util.List;

/**
 * 异常到 HTTP 响应的映射。
 *
 * <p>把 {@code handle} 里那一长串 catch 收口成一个纯映射：给定一个异常，返回该回什么
 * 状态码、什么错误码、什么消息，以及要不要带 {@code Retry-After}。有副作用的两条（存储故障、
 * 兜底 500）的日志也在这里落，免得每个端点各自记一遍。
 *
 * <p>实现是<strong>责任链</strong>：每个异常类型对应一个 {@link Handler}，按优先级排进
 * {@link #HANDLERS}。要新增一种异常的映射，是往链上加一个处理器，而不是钻进一个长方法的中间去改——
 * 这也让每条映射可以单独测。链走完还没人认领，就落到兜底的 500（对外脱敏、对内留痕）。
 * 顺序与"最具体者优先"一致：{@link BodyTooLarge} 这样的请求层异常要先于更泛的
 * {@link IllegalArgumentException} 与兜底。
 */
final class HttpErrorMapper {

    /** 一条错误响应该有的全部信息。 */
    record Action(int status, String code, String message, boolean retryAfter) {}

    /**
     * 责任链上的一环。
     *
     * <p>认领自己负责的异常类型并返回对应的 {@link Action}；不负责则返回 {@code null}，
     * 让链上的下一个处理器继续尝试。
     */
    @FunctionalInterface
    interface Handler {
        Action handle(Throwable e);
    }

    private static final System.Logger LOG = System.getLogger(HttpErrorMapper.class.getName());

    /** 责任链。顺序即优先级：越具体的异常越靠前，兜底处理链尾由 {@link #map} 自己收。 */
    private static final List<Handler> HANDLERS = List.of(
            HttpErrorMapper::bodyTooLarge,
            HttpErrorMapper::badJson,
            HttpErrorMapper::badRequest,
            HttpErrorMapper::unsupported,
            HttpErrorMapper::deadlineExceeded,
            HttpErrorMapper::cannotEvaluate,
            HttpErrorMapper::historyTruncated,
            HttpErrorMapper::storage);

    static Action map(Throwable e) {
        for (var handler : HANDLERS) {
            var action = handler.handle(e);
            if (action != null) {
                return action;
            }
        }
        // 对外脱敏（消息可能带元组内容），对内必须留痕
        LOG.log(System.Logger.Level.ERROR, "判定失败", e);
        return new Action(500, "internal_error", "判定失败", false);
    }

    /** 请求体超限：readAllBytes 不限长度，一个大 body 就能耗尽堆。 */
    private static Action bodyTooLarge(Throwable e) {
        return e instanceof BodyTooLarge b
                ? new Action(413, "payload_too_large", b.getMessage(), false)
                : null;
    }

    /**
     * 反序列化失败是调用方的问题。它是 {@link JacksonException}（{@code IOException} 的子类），
     * 不单独接住就会逃出 handle，客户端拿到的是空响应而不是 400。线格式的必填校验抛的
     * {@link IllegalArgumentException} 会被 Jackson 包一层，把它拆出来，好让调用方知道是哪个字段
     * 而不是笼统的"解析失败"。
     */
    private static Action badJson(Throwable e) {
        if (!(e instanceof JacksonException)) {
            return null;
        }
        var detail = e.getCause() instanceof IllegalArgumentException cause
                ? cause.getMessage()
                : "请求体无法解析";
        return new Action(400, "bad_request", detail, false);
    }

    /** 策略/请求层面的错误：是调用方的问题，不是服务端故障。 */
    private static Action badRequest(Throwable e) {
        return e instanceof SchemaException || e instanceof IllegalArgumentException
                ? new Action(400, "bad_request", e.getMessage(), false)
                : null;
    }

    private static Action unsupported(Throwable e) {
        return e instanceof UnsupportedOperationException u
                ? new Action(405, "not_supported", u.getMessage(), false)
                : null;
    }

    /**
     * 504 而不是 422：期限到了说明这次"慢"，换个时刻可能就过了，重试是合理的；
     * 而 422 的语义是"这个请求本身无法求值"，网关不会重试它。
     */
    private static Action deadlineExceeded(Throwable e) {
        return e instanceof DeadlineExceededException
                ? new Action(504, "deadline_exceeded", "判定超过请求期限", true)
                : null;
    }

    private static Action cannotEvaluate(Throwable e) {
        return e instanceof EvalException ev
                ? new Action(422, "cannot_evaluate", ev.getMessage(), false)
                : null;
    }

    /**
     * 409 而不是 400：错的不是这次请求的参数，而是<strong>客户端的状态与服务端不再一致</strong>——
     * 它落后得太多，那一段的撤销记录已经被回收掉了。正确处置是丢弃本地缓存重新开始，而不是改参数重试。
     * 给 400 会把调用方引向"换个 from 再试"，而任何还能返回数据的 from 都意味着它继续拿着一份缺了
     * 撤销的缓存放行已收回的权限。
     */
    private static Action historyTruncated(Throwable e) {
        if (!(e instanceof HistoryTruncatedException h)) {
            return null;
        }
        return new Action(409, "history_truncated",
                "起始坐标早于历史回收水位 " + h.earliest().value()
                        + "：这一段的撤销记录已被删除。请丢弃本地缓存，从当前 HEAD 重新开始",
                false);
    }

    /**
     * 可重试的存储故障给 503 而不是 500：500 的语义是"服务端有 bug"，网关不会重试；
     * 而连接断开、死锁、语句超时恰恰重试一次就好。两者混成同一个码，调用方只能在"全都重试"和
     * "全都不重试"之间选，两个都错。
     */
    private static Action storage(Throwable e) {
        if (!(e instanceof StorageException s)) {
            return null;
        }
        LOG.log(System.Logger.Level.WARNING, "存储故障，可重试=" + s.retryable(), s);
        return s.retryable()
                ? new Action(503, "storage_unavailable", "存储暂时不可用，请重试", true)
                : new Action(500, "internal_error", "判定失败", false);
    }
}

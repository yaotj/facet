package facet.core.runtime;

/**
 * 墙钟期限已到。
 *
 * <p>单独一个类型，而不是复用 {@link EvalException}：调用方对这两件事的处置不同。
 * 工作预算耗尽说明形状不对，重试一次结果一样，HTTP 上是 422；期限到了说明这次<em>慢</em>，
 * 换个时刻可能就过了，HTTP 上是 504，而且可以重试。混成一个类型，网关就只能一视同仁。
 *
 * <p>和预算耗尽一样，它<strong>不能</strong>落成 deny：结论是"不知道"，而 deny 会被调用方
 * 当成答案。
 */
public final class DeadlineExceededException extends EvalException {

    public DeadlineExceededException(String message) {
        super(message);
    }
}

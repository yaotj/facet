package facet.core.runtime;

import java.time.Duration;

/**
 * 一次请求的墙钟期限。
 *
 * <p>与工作预算（{@code Ctx.Request.maxNodes}）分工不同，两者都要有：
 * <ul>
 *   <li><strong>预算限总工作量，是确定性的。</strong>同一份数据同一次请求，撞不撞预算的结论
 *       每次都一样，可复现、可写进测试。它挡的是"这个形状会把存储打穿"。</li>
 *   <li><strong>期限限墙钟，是不确定性的。</strong>同一次请求在空闲时通过、在高负载时超时。
 *       它挡的是"存储今天比平时慢十倍"——那种情况下工作量完全合规，但调用方已经等不起了。</li>
 * </ul>
 *
 * <p>存绝对时刻而不是 {@code Duration}：请求在多个求值器之间流转（{@code checkAll} 会对
 * 一批对象各跑一次 check），存相对时长的话每一段都会重新开始计时，整个请求的总时长就没有上限。
 *
 * <p><strong>期限只有被传导下去才真的生效。</strong>在求值节点之间检查只能保证"不再发起新的
 * 存储调用"，已经在途的那一次仍会跑到它自己的超时。所以 {@link #remaining()} 是契约的一部分：
 * 适配器应当用它去收紧语句超时，并发扇出应当用它去收紧 scope 超时。
 */
public final class Deadline {

    /** 不设期限。默认值——墙钟上限属于部署决策，库不替使用方选。 */
    public static final Deadline NONE = new Deadline(false, 0);

    private final boolean bounded;
    private final long atNanos;

    private Deadline(boolean bounded, long atNanos) {
        this.bounded = bounded;
        this.atNanos = atNanos;
    }

    /**
     * 从现在起 {@code budget} 之后到期。
     *
     * <p>在构造时就把时刻定下来，而不是在第一次检查时——否则"期限"会随着请求在哪一步
     * 第一次被检查而漂移。
     */
    public static Deadline after(Duration budget) {
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("期限必须为正；不设期限用 Deadline.NONE");
        }
        return new Deadline(true, System.nanoTime() + budget.toNanos());
    }

    /** 是否设了期限。 */
    public boolean bounded() {
        return bounded;
    }

    /** 已经到期。 */
    public boolean expired() {
        // 相减再比较，而不是直接比大小：nanoTime 允许回绕，相减的结果才是有意义的
        return bounded && atNanos - System.nanoTime() <= 0;
    }

    /**
     * 还剩多久。
     *
     * @return 未设期限时返回 {@code null}——让调用方必须显式处理"没有上限"这一支，
     *         而不是拿到一个 {@code Duration.ZERO} 或 {@code Long.MAX_VALUE} 去猜含义
     */
    public Duration remaining() {
        if (!bounded) {
            return null;
        }
        long left = atNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    /**
     * 用期限收紧一个既有的超时值。
     *
     * <p>取两者的较小值：期限比语句超时短时，不该让一条语句跑满它自己的超时；
     * 语句超时更短时也不该被期限放宽。
     *
     * @param timeout 适配器自己配置的超时
     * @return 实际应当使用的超时；已经到期则返回 {@link Duration#ZERO}
     */
    public Duration clamp(Duration timeout) {
        var left = remaining();
        if (left == null) {
            return timeout;
        }
        return left.compareTo(timeout) < 0 ? left : timeout;
    }
}

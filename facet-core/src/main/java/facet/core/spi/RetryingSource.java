package facet.core.spi;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在可重试故障上自动重试的装饰器。
 *
 * <p>同时包装 {@link TupleSource} 与 {@link AttrSource}：check 路径上元组读和属性读交替发生，
 * 只包一半就会出现"元组读扛住了抖动但属性读没扛住"——而这两步在外面看起来是同一次判定。
 *
 * <p>重试策略的三个参数都必须显式给：
 * <ul>
 *   <li><strong>次数。</strong>默认 1 次就是不重试。增加重试次数就是增加一次请求在故障时
 *       施加的总压力，必须由了解存储容量的人来定。</li>
 *   <li><strong>退避。</strong>前几次不退避，因为连接池切换通常在毫秒级；后几次退避，
 *       防止慢恢复时客户端把压力叠上去。</li>
 *   <li><strong>哪些异常重试。</strong>只重试 {@link StorageException#retryable()}，
 *       其余一律透出。适配器已经把 {@code SQLState} 分好了类，这里不再看错误码。</li>
 * </ul>
 *
 * <p><strong>幂等性。</strong>check / expand / lookup 三条读路径天然幂等，重试不改变语义。
 * 写路径（{@code PgTupleSource.apply}）<em>不在</em>这个装饰器的包装范围内——它不是端口方法，
 * 重试一个非幂等的写操作应当由调用方显式决定，不该被装饰器静默做掉。
 */
public final class RetryingSource implements TupleSource, AttrSource {

    /**
     * 默认退避序列。
     *
     * <p>第一次立即重试（连接池切连接的代价是毫秒级），后续按 50/200ms 退避。
     * 三次总共最多 250ms 额外延迟。大于三次几乎总是错的——连续失败说明不是抖动而是故障，
     * 继续重试只是在消耗连接预算。
     */
    private static final Duration[] DEFAULT_BACKOFFS = {Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(200)};

    private final TupleSource tuples;
    private final AttrSource attrs;
    private final Duration[] backoffs;

    /**
     * @param tuples   被装饰的元组源
     * @param attrs    被装饰的属性源
     * @param maxRetries 最多重试几次（不含首次）。0 表示不重试——那就只是一个透传包装，没有意义
     * @param backoffs 每次重试前的退避间隔。长度不足 {@code maxRetries} 时最后一个值重复使用
     */
    public RetryingSource(TupleSource tuples, AttrSource attrs, int maxRetries, Duration... backoffs) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("重试次数不能为负");
        }
        if (backoffs.length == 0 && maxRetries > 0) {
            throw new IllegalArgumentException("重试次数 > 0 时必须至少给一个退避间隔");
        }
        this.tuples = tuples;
        this.attrs = attrs;
        // 展开成定长数组：运行时不做"索引超长则取末尾"的分支
        this.backoffs = new Duration[maxRetries];
        for (int i = 0; i < maxRetries; i++) {
            this.backoffs[i] = backoffs[Math.min(i, backoffs.length - 1)];
        }
    }

    /** 使用默认退避序列（立即 / 50ms / 200ms），最多重试三次。 */
    public RetryingSource(TupleSource tuples, AttrSource attrs) {
        this(tuples, attrs, DEFAULT_BACKOFFS.length, DEFAULT_BACKOFFS);
    }

    // ---- TupleSource ----

    @Override
    public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
        return retry(() -> tuples.subjects(obj, rel));
    }

    @Override
    public Set<ObjectRef> targets(ObjectRef obj, Rel hop) {
        return retry(() -> tuples.targets(obj, hop));
    }

    @Override
    public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
        // Stream 在被消费前不会碰存储，所以物化到 List 再转 Stream：
        // 让重试的粒度是"整次查询"而不是"迭代到第 N 行时发现连接断了"
        var list = retry(() -> tuples.objects(subject, rel, type).toList());
        return list.stream();
    }

    @Override
    public Caps caps() {
        return tuples.caps();
    }

    // ---- AttrSource ----

    @Override
    public Object value(AttrKey key, ObjectRef obj) {
        return retry(() -> attrs.value(key, obj));
    }

    @Override
    public Map<ObjectRef, Object> values(AttrKey key, Collection<ObjectRef> objects) {
        return retry(() -> attrs.values(key, objects));
    }

    // ---- 重试机制 ----

    @FunctionalInterface
    private interface Op<T> {
        T call();
    }

    private <T> T retry(Op<T> op) {
        StorageException last = null;
        // attempt 0 = 首次；attempt 1..N = 重试
        for (int attempt = 0; attempt <= backoffs.length; attempt++) {
            if (attempt > 0) {
                sleep(backoffs[attempt - 1]);
            }
            try {
                return op.call();
            } catch (StorageException e) {
                if (!e.retryable()) {
                    throw e;
                }
                last = e;
            }
        }
        // 所有重试耗尽
        throw last;
    }

    /** {@code Thread.sleep} 包一层，好让虚拟线程在退避时释放载体线程。 */
    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("重试被中断", e, true);
        }
    }
}

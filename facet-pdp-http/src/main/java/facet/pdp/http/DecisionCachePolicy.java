package facet.pdp.http;

import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.spi.DecisionCache;
import facet.core.spi.RevisionSource;

import java.time.Duration;
import java.util.Map;

/**
 * 判定缓存与坐标陈旧策略。
 *
 * <p>两个职责都围着"在哪个坐标上、要不要缓存"转：{@link #cacheKey} 决定一条请求能不能进缓存，
 * {@link #resolveAt} 决定读 HEAD 时钉到哪个略微陈旧但新鲜的坐标上。两者共享同一组可选能力，
 * 且 {@code resolveAt} 维护的钉点水位是带状态的服务端字段，所以做成有状态的实例而不是静态工具。
 */
final class DecisionCachePolicy {

    private final DecisionCache cache;
    private final RevisionSource revisions;
    private final Duration staleness;
    /** 钉住的坐标水位。volatile 就够：过期重取是幂等的，多取一次只是多一次水位查询。 */
    private volatile Pinned pinned;

    DecisionCachePolicy(DecisionCache cache, RevisionSource revisions, Duration staleness) {
        this.cache = cache;
        this.revisions = revisions;
        this.staleness = staleness;
    }

    /**
     * 判定要在哪个坐标上求值。
     *
     * <p>请求给了坐标就用它；没给则看是否配置了陈旧窗口——配了就钉到一个刷新过的水位上。
     * 钉住的坐标同时是缓存键与求值坐标，两者必须一致，否则缓存里存的是另一个世界的答案。
     */
    Revision resolveAt(Long requested) {
        if (requested != null) {
            return new Revision(requested);
        }
        if (staleness.isZero()) {
            return Revision.HEAD;
        }
        var snapshot = pinned;
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.nanos() < staleness.toNanos()) {
            return snapshot.revision();
        }
        var fresh = revisions.head();
        if (fresh.isHead()) {
            return Revision.HEAD;
        }
        pinned = new Pinned(fresh, now);
        return fresh;
    }

    /** @return 缓存键；不可缓存时返回 {@code null} */
    DecisionCache.Key cacheKey(SubjectRef subject, ObjectRef object, Rel relation,
                               Revision at, Map<String, Object> contextAttrs,
                               boolean wantExplain) {
        if (cache == DecisionCache.NONE || at.isHead() || wantExplain) {
            return null;
        }
        if (contextAttrs != null && !contextAttrs.isEmpty()) {
            // 判定依赖请求自带的属性：塞进键会让键空间爆炸，不塞进去就是缓存污染
            return null;
        }
        return new DecisionCache.Key(subject, object, relation, at);
    }

    /** 钉住的坐标水位。 */
    private record Pinned(Revision revision, long nanos) {}
}

package facet.core.eval;

import facet.core.ir.ObjectRef;
import facet.core.ir.Perm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求内记忆化。
 *
 * <p>只缓存已完成的判定，环检测交给 {@link Trail}：把"正在求值"的集合放进共享 Memo，
 * 在并行扇出下会把兄弟分支同时求值同一个 key 误判成环。
 *
 * <p>并发安全，因为扇出可能是并行的；重复计算是良性的，脏读不是。
 */
public final class Memo {

    /** {@code Perm} 是 record，结构相等，可以直接当 key。 */
    public record Key(Perm perm, ObjectRef obj) {}

    private final Map<Key, Decision> cache = new ConcurrentHashMap<>();

    /** 未命中返回 {@code null} 而非 {@code Optional}：递归的每一层都要走这一步，包装对象的分配不值得。 */
    public Decision get(Key key) {
        return cache.get(key);
    }

    /**
     * 带剪枝的判定不入缓存。{@code CycleCut} 与 {@code DepthExceeded} 只对<strong>当前路径</strong>成立，
     * 缓存下来会让另一条本可以走通的路径读到假的 deny。
     */
    public void put(Key key, Decision decision) {
        if (!Explain.hasCut(decision.explain())) {
            cache.put(key, decision);
        }
    }

    /** 给可观测性用：一次请求缓存了多少个判定，是"这条 schema 形状该不该改"的直接依据。 */
    public int size() {
        return cache.size();
    }
}

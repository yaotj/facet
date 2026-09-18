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

    public Decision get(Key key) {
        return cache.get(key);
    }

    public void put(Key key, Decision decision) {
        if (!Explain.hasCut(decision.explain())) {
            cache.put(key, decision);
        }
    }

    public int size() {
        return cache.size();
    }
}

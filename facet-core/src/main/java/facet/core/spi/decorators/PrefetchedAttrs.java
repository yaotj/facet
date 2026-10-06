package facet.core.spi.decorators;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.spi.AttrSource;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 预取过的属性源。
 *
 * <p>批量判定前把这一批对象的属性一次取回，之后逐个对象求值时命中内存。这样
 * {@code Checker} 不必知道"批量"这件事——批量只是入口处的一层装饰。
 *
 * <p>刻意记录<strong>缺失</strong>而不是遇缺就回源：属性不存在也是一个确定答案，
 * 回源会让"这个对象没有 region 属性"变成每次判定一次外部调用，正是要消除的那个 N+1。
 *
 * <p>预取清单来自 {@link Attrs#localKeys}，只覆盖入口对象自身的属性。{@code Through}
 * 之后的对象由数据决定，那部分仍然逐条回源——这是已知边界，不是遗漏。
 */
public final class PrefetchedAttrs implements AttrSource {

    private record Slot(AttrKey key, ObjectRef obj) {}

    private final AttrSource delegate;
    private final Map<Slot, Object> prefetched;
    private final Set<Slot> known;

    private PrefetchedAttrs(AttrSource delegate, Map<Slot, Object> prefetched, Set<Slot> known) {
        this.delegate = delegate;
        this.prefetched = prefetched;
        this.known = known;
    }

    /**
     * 为一批对象预取给定的属性键。
     *
     * @param keys 通常来自 {@link Attrs#localKeys}
     */
    public static PrefetchedAttrs of(AttrSource delegate,
                                     Set<AttrKey> keys,
                                     Collection<ObjectRef> objects) {
        var values = new HashMap<Slot, Object>();
        var known = new java.util.HashSet<Slot>();
        for (var key : keys) {
            var batch = delegate.values(key, objects);
            for (var obj : objects) {
                var slot = new Slot(key, obj);
                known.add(slot);
                var value = batch.get(obj);
                if (value != null) {
                    values.put(slot, value);
                }
            }
        }
        return new PrefetchedAttrs(delegate, Map.copyOf(values), Set.copyOf(known));
    }

    @Override
    public Object value(AttrKey key, ObjectRef obj) {
        var slot = new Slot(key, obj);
        return known.contains(slot) ? prefetched.get(slot) : delegate.value(key, obj);
    }

    @Override
    public Map<ObjectRef, Object> values(AttrKey key, Collection<ObjectRef> objects) {
        return delegate.values(key, objects);
    }
}

package facet.store.memory;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.spi.AttrSource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存属性源。
 *
 * <p>{@code SNAPSHOT} 与 {@code EXTERNAL} 分开存放，并且记录 {@code EXTERNAL} 的读取次数：
 * "外部属性有 IO 成本"这件事只有能被计量，才可能在测试里被断言。
 */
public final class MemoryAttrSource implements AttrSource {

    private record Slot(ObjectRef obj, String name) {}

    private final Map<Slot, Object> snapshot = new ConcurrentHashMap<>();
    private final Map<Slot, Object> external = new ConcurrentHashMap<>();
    // 并行扇出下 ++ 会丢计数，而这个计数器存在的理由正是让"外部属性有 IO 成本"可被断言
    private final AtomicInteger externalReads = new AtomicInteger();

    public MemoryAttrSource put(ObjectRef obj, AttrKey key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("属性值不能为 null: " + key.name());
        }
        var target = switch (key.tier()) {
            case SNAPSHOT -> snapshot;
            case EXTERNAL -> external;
            case CONTEXT -> throw new IllegalArgumentException(
                    "CONTEXT 属性由请求自带，不经过 AttrSource: " + key.name());
        };
        target.put(new Slot(obj, key.name()), value);
        return this;
    }

    @Override
    public Object value(AttrKey key, ObjectRef obj) {
        var slot = new Slot(obj, key.name());
        return switch (key.tier()) {
            case SNAPSHOT -> snapshot.get(slot);
            case EXTERNAL -> {
                externalReads.incrementAndGet();
                yield external.get(slot);
            }
            case CONTEXT -> throw new IllegalArgumentException(
                    "CONTEXT 属性由请求自带，不经过 AttrSource: " + key.name());
        };
    }

    /** EXTERNAL 属性被读取的次数。用来断言批量化是否生效。 */
    public int externalReads() {
        return externalReads.get();
    }

    /**
     * 批量读取。一次批量只记一次读取——这个计数器存在的意义就是让"批量化生效了没有"
     * 可以被断言，而不是靠看代码猜。
     */
    @Override
    public Map<ObjectRef, Object> values(AttrKey key, Collection<ObjectRef> objects) {
        var source = switch (key.tier()) {
            case SNAPSHOT -> snapshot;
            case EXTERNAL -> {
                externalReads.incrementAndGet();
                yield external;
            }
            case CONTEXT -> throw new IllegalArgumentException(
                    "CONTEXT 属性由请求自带，不经过 AttrSource: " + key.name());
        };
        var out = new LinkedHashMap<ObjectRef, Object>();
        for (var obj : objects) {
            var value = source.get(new Slot(obj, key.name()));
            if (value != null) {
                out.put(obj, value);
            }
        }
        return out;
    }
}

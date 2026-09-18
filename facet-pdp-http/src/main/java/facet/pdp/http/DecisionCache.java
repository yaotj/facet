package facet.pdp.http;

import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判定缓存。
 *
 * <p>缓存键必须带一致性坐标。这是缓存权限判定唯一安全的方式：坐标是不可变的，
 * 一次写入推进坐标之后旧键再也不会被命中，失效变成"自然过期"而不是要去逐条清理——
 * 而逐条清理在关系图上做不到，一条元组变更影响哪些判定是数据决定的。
 *
 * <p>因此<strong>只有携带具体坐标的请求会被缓存</strong>。读 HEAD 的请求要么绕过缓存，
 * 要么由部署方显式接受有界陈旧（见 {@link RevisionSource} 与
 * {@code PdpServer.Config#staleness}）。库不替使用方选择陈旧度。
 *
 * <p>另外两类请求也一律绕过：
 * <ul>
 *   <li>带 CONTEXT 属性的——判定依赖请求自带的属性，把它们塞进键会让键空间爆炸，
 *       不塞进去就是缓存污染。</li>
 *   <li>要 explain 的——判定树是排查用的，缓存它没有意义还很占内存。</li>
 * </ul>
 */
public interface DecisionCache {

    /** 缓存键。坐标是键的一部分，因此写入推进坐标即天然失效。 */
    record Key(SubjectRef subject, ObjectRef object, Rel relation, Revision at) {}

    /** @return 命中的判定结论，未命中返回 {@code null} */
    Boolean get(Key key);

    /** 写入结论。实现可以自行淘汰甚至直接丢弃：这里存的是可重算的结果，不是权威数据。 */
    void put(Key key, boolean allowed);

    /** 不缓存。默认值：缓存是可选优化，不该是默认行为。 */
    DecisionCache NONE = new DecisionCache() {

        @Override
        public Boolean get(Key key) {
            return null;
        }

        @Override
        public void put(Key key, boolean allowed) {
        }
    };

    /**
     * 有界 LRU 缓存。
     *
     * <p>容量上限不是可选项：键里含对象 id，而对象 id 来自请求，无界缓存就是一个
     * 由客户端控制的内存泄漏。
     */
    static DecisionCache bounded(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("缓存容量必须为正");
        }
        var lru = new LinkedHashMap<Key, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Boolean> eldest) {
                return size() > capacity;
            }
        };
        var guarded = Collections.synchronizedMap(lru);
        return new DecisionCache() {

            @Override
            public Boolean get(Key key) {
                return guarded.get(key);
            }

            @Override
            public void put(Key key, boolean allowed) {
                guarded.put(key, allowed);
            }
        };
    }
}

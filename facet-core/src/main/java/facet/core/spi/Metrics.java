package facet.core.spi;

import facet.core.ir.AttrKey;
import facet.core.ir.Rel;

/**
 * 观测挂点。
 *
 * <p>刻意只有三个方法，对应三个会在生产上真正出问题的量：
 * <ul>
 *   <li><strong>判定延迟。</strong>解释器的深度由数据决定，延迟分布的长尾就是层级异常深的
 *       那部分数据。没有这个数就只能等用户来报"某些资源打开很慢"。</li>
 *   <li><strong>扇出宽度。</strong>热点对象（几万人的组、挂了几万文档的目录）在这里现形。
 *       {@code Caps.maxFanout} 是硬拒绝，而这个量能在撞上限之前给出预警。</li>
 *   <li><strong>属性批量大小。</strong>批量判定的全部意义就是把它从 1 变成 N。
 *       它一旦长期是 1，说明预取没生效，而结果完全正确、只是慢——最难发现的那种退化。</li>
 * </ul>
 *
 * <p>不引入任何指标库：埋点是端口，聚合是部署方的事。默认 {@link #NOOP}，
 * 因为观测应当是显式装上的能力，而不是内核偷偷带上的依赖。
 */
public interface Metrics {

    /** 一次判定完成。 */
    void decision(Rel relation, boolean allowed, long elapsedNanos);

    /** 一次扇出的宽度。{@code operator} 形如 {@code Through(parent)}。 */
    void fanout(String operator, int width);

    /** 一次属性读取的批量大小。1 表示逐条，批量化没有生效。 */
    void attributeBatch(AttrKey key, int size);

    /** 不观测。 */
    Metrics NOOP = new Metrics() {

        @Override
        public void decision(Rel relation, boolean allowed, long elapsedNanos) {
        }

        @Override
        public void fanout(String operator, int width) {
        }

        @Override
        public void attributeBatch(AttrKey key, int size) {
        }
    };
}

package facet.pdp.http;

import facet.core.ir.Revision;

/**
 * 坐标水位来源。
 *
 * <p>只有一个用途：把读 HEAD 的请求钉到一个<strong>具体</strong>坐标上，让它们也能进缓存。
 * 代价是有界陈旧——钉住的坐标会在配置的时间窗内被复用，窗内的新授权看不到、
 * 窗内的撤销也仍然生效。这是一个必须由部署方明确接受的取舍，所以做成显式配置：
 * 不配就不缓存 HEAD，绝不默认引入陈旧。
 *
 * <p>{@code PgTupleSource::head} 直接可用作实现。
 */
@FunctionalInterface
public interface RevisionSource {

    /** @return 当前可读到的最新坐标；实现无法给出具体值时返回 {@code Revision.HEAD}，此时不会进缓存 */
    Revision head();

    /** 不提供水位。HEAD 请求因此不进缓存。 */
    RevisionSource NONE = () -> Revision.HEAD;
}

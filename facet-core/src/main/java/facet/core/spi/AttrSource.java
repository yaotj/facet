package facet.core.spi;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;

/**
 * 属性来源（PIP）端口。
 *
 * <p>只负责 {@code SNAPSHOT} 与 {@code EXTERNAL} 两级；{@code CONTEXT} 属性由请求自带，
 * 不经过这里——让它们走同一个端口会让"无 IO"这个保证消失。
 */
public interface AttrSource {

    /**
     * 取对象上的属性值。
     *
     * @param key 必须是 {@code SNAPSHOT} 或 {@code EXTERNAL} 等级
     */
    Object value(AttrKey key, ObjectRef obj);
}

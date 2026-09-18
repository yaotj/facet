package facet.core.spi;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * 批量取同一个属性在多个对象上的值。
     *
     * <p>批量判定必须走这条路。{@code EXTERNAL} 属性每次求值都是一次外部调用，逐条问
     * 就是把 N+1 问题搬进授权判定——一次"这 200 个文档我能看哪些"会变成 200 次 HR 系统调用。
     *
     * <p>默认实现逐条回落，好让适配器可以只实现单条版本；真实的 PIP 与数据库都应当
     * override 成一次调用。返回的 map 允许缺键，缺键即"该对象上没有这个属性"。
     */
    default Map<ObjectRef, Object> values(AttrKey key, Collection<ObjectRef> objects) {
        var out = new LinkedHashMap<ObjectRef, Object>();
        for (var obj : objects) {
            var value = value(key, obj);
            if (value != null) {
                out.put(obj, value);
            }
        }
        return out;
    }
}

package facet.pdp.http;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 线格式与内核 IR 之间的双向映射。
 *
 * <p>全是纯函数：不碰服务端状态、不读配置，只把 {@link Wire} 上的 JSON 形状翻译成内核的
 * {@link Tuple}/{@link SubjectRef}/{@link ObjectRef}/{@link TupleFilter}，反过来也一样。
 * 挪出来之后，HTTP 处理链只管"收到请求、回响应"，格式转换的边界一眼可见，也能脱离服务器单独测。
 */
final class WireCodec {

    private WireCodec() {}

    static List<Tuple> tuples(List<Wire.TupleJson> wire) {
        if (wire == null) {
            return List.of();
        }
        var out = new ArrayList<Tuple>(wire.size());
        for (var item : wire) {
            out.add(tuple(item));
        }
        return out;
    }

    static Tuple tuple(Wire.TupleJson item) {
        SubjectRef subject;
        if (SubjectRef.WILDCARD_ID.equals(item.subject().id())) {
            // user:* → Wildcard(user)
            subject = new SubjectRef.Wildcard(new ObjectType(item.subject().type()));
        } else if (item.subjectRelation() == null) {
            subject = new SubjectRef.Principal(
                    new ObjectType(item.subject().type()), item.subject().id());
        } else {
            subject = new SubjectRef.Userset(ref(item.subject()), new Rel(item.subjectRelation()));
        }
        return new Tuple(ref(item.object()), new Rel(item.relation()), subject);
    }

    static Wire.TupleJson json(Tuple tuple) {
        var object = new Wire.Ref(tuple.object().type().name(), tuple.object().id());
        return switch (tuple.subject()) {
            case SubjectRef.Principal(var type, var id) -> new Wire.TupleJson(
                    object, tuple.relation().name(), new Wire.Ref(type.name(), id), null);
            case SubjectRef.Userset(var target, var relation) -> new Wire.TupleJson(
                    object, tuple.relation().name(),
                    new Wire.Ref(target.type().name(), target.id()), relation.name());
            case SubjectRef.Wildcard(var type) -> new Wire.TupleJson(
                    object, tuple.relation().name(),
                    new Wire.Ref(type.name(), SubjectRef.WILDCARD_ID), null);
        };
    }

    /**
     * 线格式的筛选条件转内核形状。
     *
     * <p>走规范构造器而不是那些工厂方法：工厂方法各自钉住了特定字段（"某个对象上的全部"、
     * "某个主体的全部"），而线格式允许任意子集，包括只给 {@code type} 不给 {@code id}。
     * {@code null} 一路保留为"任意"，空白串则会被 {@code TupleFilter} 挡掉——
     * 那正是我们想要的：把空串当成"任意"是个安静的灾难。
     */
    static TupleFilter filter(Wire.TupleFilterJson wire) {
        var object = wire.object();
        var subject = wire.subject();
        return new TupleFilter(
                object == null || object.type() == null ? null : new ObjectType(object.type()),
                object == null ? null : object.id(),
                wire.relation() == null ? null : new Rel(wire.relation()),
                subject == null || subject.type() == null ? null : new ObjectType(subject.type()),
                subject == null ? null : subject.id(),
                wire.subjectRelation() == null ? null : new Rel(wire.subjectRelation()));
    }

    static SubjectRef subject(Wire.Ref wire) {
        return new SubjectRef.Principal(new ObjectType(wire.type()), wire.id());
    }

    static ObjectRef ref(Wire.Ref wire) {
        return new ObjectRef(new ObjectType(wire.type()), wire.id());
    }
}

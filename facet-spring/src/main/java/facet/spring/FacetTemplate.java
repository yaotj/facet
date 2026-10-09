package facet.spring;

import facet.core.eval.Expander;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Decision;
import facet.sdk.Facet;

import java.util.Collection;
import java.util.List;
import java.util.SequencedMap;

/**
 * Facet 的类型安全判定客户端（门面的薄封装）。
 *
 * <p>应用直接 {@code @Autowired FacetTemplate}，不必关心 {@code Ctx}/{@code Revision} 这些内核细节；
 * 单点判定另给 {@link #isAllowed} 直接拿到布尔。需要坐标 / 批量 / 反查 / 展开时走对应方法即可。
 */
public class FacetTemplate {

    private final Facet facet;

    public FacetTemplate(Facet facet) {
        this.facet = facet;
    }

    /** 便捷布尔判定：{@code subject} 能否对 {@code object} 做 {@code relation}（读最新）。 */
    public boolean isAllowed(SubjectRef subject, ObjectRef object, Rel relation) {
        return facet.check(subject, object, relation).allowed();
    }

    public Decision check(SubjectRef subject, ObjectRef object, Rel relation) {
        return facet.check(subject, object, relation);
    }

    public Decision checkAt(SubjectRef subject, ObjectRef object, Rel relation, Revision at) {
        return facet.checkAt(subject, object, relation, at);
    }

    public SequencedMap<ObjectRef, Decision> checkAll(
            SubjectRef subject, Collection<ObjectRef> objects, Rel relation) {
        return facet.checkAll(subject, objects, relation);
    }

    public List<ObjectRef> lookup(SubjectRef subject, ObjectType objectType, Rel relation, int limit) {
        return facet.lookup(subject, objectType, relation, limit);
    }

    public Expander.Subjects whoCan(ObjectRef object, Rel relation, int limit) {
        return facet.whoCan(object, relation, limit);
    }
}

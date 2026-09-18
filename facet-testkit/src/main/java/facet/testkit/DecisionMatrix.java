package facet.testkit;

import facet.core.eval.Ctx;
import facet.core.eval.Decision;
import facet.core.eval.Explains;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;

import java.util.List;
import java.util.Map;

/**
 * 判定矩阵：主体 × 资源 × 关系的完整判定表，连同命中路径。
 *
 * <p>策略改动时 diff 这张表。这是唯一能看出"这次改动意外放开了什么"的手段——逐个
 * {@code check} 的单元测试永远覆盖不到组合爆炸，而权限回归没有第二条可靠防线。
 *
 * <p>上下文由这里绑定，而不是让调用方在外面先 {@code Ctx.run} 再传探针：主体是矩阵的
 * 一个维度，如果绑定在外面，整张表都会用同一个主体求值，而表面上看不出来。
 */
public final class DecisionMatrix {

    /** 判定探针。在已绑定好上下文的作用域内被调用，因此不需要接收主体。 */
    public interface Probe {
        Decision check(ObjectRef object, Rel relation);
    }

    private DecisionMatrix() {
    }

    /** 不带上下文属性的矩阵。CONTEXT 属性缺失时条件不成立，因此带条件的关系在这里一律 DENY。 */
    public static String render(List<SubjectRef> subjects,
                                List<ObjectRef> objects,
                                List<Rel> relations,
                                Probe probe) {
        return render(subjects, objects, relations, Map.of(), probe);
    }

    /**
     * 渲染整张矩阵。
     *
     * <p>输出顺序严格按 subjects × objects × relations 的入参顺序，主体名走
     * {@code Cursor.keyOf}——顺序与命名都定死了，快照 diff 才只反映判定本身的变化。
     *
     * @param contextAttrs 整张表共用的 CONTEXT 属性；要对比不同上下文就渲染两张表再 diff
     * @return 每行一条判定，紧随其后是缩进四格的命中路径
     */
    public static String render(List<SubjectRef> subjects,
                                List<ObjectRef> objects,
                                List<Rel> relations,
                                Map<String, Object> contextAttrs,
                                Probe probe) {
        var out = new StringBuilder();
        for (var subject : subjects) {
            var request = Ctx.Request.of(subject).withContextAttrs(contextAttrs);
            for (var object : objects) {
                for (var relation : relations) {
                    var decision = Ctx.run(request, () -> probe.check(object, relation));
                    out.append(name(subject)).append(" | ")
                            .append(object.type().name()).append(':').append(object.id()).append(" | ")
                            .append(relation.name()).append(" | ")
                            .append(decision.allowed() ? "ALLOW" : "DENY").append('\n');
                    Explains.render(decision.explain()).lines()
                            .forEach(line -> out.append("    ").append(line).append('\n'));
                }
            }
        }
        return out.toString();
    }

    private static String name(SubjectRef subject) {
        // 复用 Cursor.keyOf：type:id 这个键同时是分页排序键与 PG 侧 COLLATE "C" 的对齐目标，
        // 各处自己拼一遍就会让 golden 基线、游标和 explain 文本慢慢对不齐
        return switch (subject) {
            case SubjectRef.Principal(var type, var id) ->
                    Cursor.keyOf(new ObjectRef(type, id));
            case SubjectRef.Userset(var object, var relation) ->
                    Cursor.keyOf(object) + '#' + relation.name();
        };
    }
}

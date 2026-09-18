package facet.testkit;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 策略变更的影响分析。
 *
 * <p>热更新让策略可以在线替换，但"这次改动到底放开了什么"没有工具回答就只能靠 review 眼力。
 * 这里把变更前后两份 schema 各跑一遍判定矩阵，只输出<strong>发生变化的那些格子</strong>。
 *
 * <p>{@code + } 是新放开的权限，{@code - } 是被收回的。放开比收回危险得多：收回会有人来报
 * 故障，放开则可能几个月都没人发现。所以输出把两类分开数，让"放开了 N 条"直接摆在眼前。
 */
public final class MatrixDiff {

    /** 一个格子的变化。 */
    public record Change(SubjectRef subject, ObjectRef object, Rel relation, boolean granted) {}

    private MatrixDiff() {
    }

    /**
     * 对比两份策略。
     *
     * @param before 变更前的判定探针
     * @param after  变更后的判定探针
     */
    public static List<Change> of(List<SubjectRef> subjects,
                                  List<ObjectRef> objects,
                                  List<Rel> relations,
                                  Map<String, Object> context,
                                  DecisionMatrix.Probe before,
                                  DecisionMatrix.Probe after) {
        var changes = new ArrayList<Change>();
        for (var subject : subjects) {
            var request = Ctx.Request.of(subject).withContextAttrs(context);
            for (var object : objects) {
                for (var relation : relations) {
                    boolean was = Ctx.run(request, () -> before.check(object, relation)).allowed();
                    boolean now = Ctx.run(request, () -> after.check(object, relation)).allowed();
                    if (was != now) {
                        changes.add(new Change(subject, object, relation, now));
                    }
                }
            }
        }
        return List.copyOf(changes);
    }

    /** 可读报告。新放开的排在前面——那是需要先看的部分。 */
    public static String render(List<Change> changes) {
        long granted = changes.stream().filter(Change::granted).count();
        var out = new StringBuilder()
                .append("新放开 ").append(granted)
                .append(" 条，收回 ").append(changes.size() - granted).append(" 条\n");
        changes.stream().filter(Change::granted).forEach(change -> append(out, change));
        changes.stream().filter(change -> !change.granted()).forEach(change -> append(out, change));
        return out.toString();
    }

    private static void append(StringBuilder out, Change change) {
        out.append(change.granted() ? "+ " : "- ")
                .append(name(change.subject())).append(" | ")
                .append(change.object().type().name()).append(':').append(change.object().id())
                .append(" | ").append(change.relation().name()).append('\n');
    }

    private static String name(SubjectRef subject) {
        return switch (subject) {
            case SubjectRef.Principal(var type, var id) -> type.name() + ':' + id;
            case SubjectRef.Userset(var object, var relation) ->
                    object.type().name() + ':' + object.id() + '#' + relation.name();
        };
    }
}

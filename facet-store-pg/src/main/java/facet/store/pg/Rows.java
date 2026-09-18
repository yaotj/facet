package facet.store.pg;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;

/** 主体在表里的三列表示，以及一致性坐标到 bigint 的映射。读写两侧都走这里，避免各写一份。 */
final class Rows {

    private Rows() {
    }

    /** 主体的三列表示。{@code rel} 为 {@code ''} 表示具体主体而非 NULL：递归 CTE 要在这一列上自连接。 */
    record Subject(String type, String id, String rel) {}

    static Subject of(SubjectRef ref) {
        return switch (ref) {
            case SubjectRef.Principal(var type, var id) -> new Subject(type.name(), id, "");
            case SubjectRef.Userset(var object, var relation) ->
                    new Subject(object.type().name(), object.id(), relation.name());
        };
    }

    static SubjectRef toSubject(String type, String id, String rel) {
        return rel.isEmpty()
                ? new SubjectRef.Principal(new ObjectType(type), id)
                : new SubjectRef.Userset(new ObjectRef(new ObjectType(type), id), new Rel(rel));
    }

    static long at() {
        return at(Ctx.current().at());
    }

    static long at(Revision revision) {
        // HEAD 映射成 OPEN - 1 而不是 OPEN：时效判定是 rev_from <= at AND at < rev_to，
        // 而当前有效行的 rev_to 就是 OPEN。若 at 也取 OPEN，严格小于不成立，
        // 结果是"读最新"什么都读不到——一个只在真实存储上才现形的边界错误。
        return revision.isHead() ? PgSchema.OPEN - 1 : revision.value();
    }

    /**
     * SNAPSHOT 属性以 text 存储，因此写入前统一按内核的归一化规则转换。
     *
     * <p>委托给 {@code Conds.text} 而不是自己写一遍 {@code String.valueOf}：那份归一化是
     * 跨适配器一致性的基准，复制一份就意味着内核改规则时这里不会跟着变。
     */
    static String text(Object value) {
        return facet.core.eval.Conds.text(value);
    }
}

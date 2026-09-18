package facet.core.ir;

import java.util.Comparator;

/**
 * 元组的主体侧。
 *
 * <p>必须是 sealed 二元结构，而不是一个 String：{@link Userset} 是把"组成员"这类间接
 * 授权表达进元组的唯一办法（{@code doc:readme#viewer@group:eng#member}）。少了它，
 * RBAC 的角色和 ReBAC 的组继承就只能靠展开成具体用户，写放大会失控。
 */
public sealed interface SubjectRef {

    /**
     * 确定的全序。
     *
     * <p>端口要求 {@code subjects()} 按此顺序返回，否则 explain 的分支顺序会随存储实现变化，
     * golden file 就只能对某一个适配器成立——而跨适配器一致性正是这套架构要保证的东西。
     * 排序键刻意与 SQL 的 {@code (subject_type, subject_id, subject_rel)} 对齐。
     */
    Comparator<SubjectRef> ORDER = Comparator
            .comparing((SubjectRef s) -> switch (s) {
                case Principal(var type, _) -> type.name();
                case Userset(var object, _) -> object.type().name();
            })
            .thenComparing(s -> switch (s) {
                case Principal(_, var id) -> id;
                case Userset(var object, _) -> object.id();
            })
            .thenComparing(s -> switch (s) {
                case Principal _ -> "";
                case Userset(_, var relation) -> relation.name();
            });

    /** 具体主体：用户、服务账号。 */
    record Principal(ObjectType type, String id) implements SubjectRef {

        /** 空 id 会让这条元组把权限授给整个类型，构造期拒绝。 */
        public Principal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("主体 id 不能为空");
            }
        }
    }

    /** 主体集合：某对象上某关系的全部主体，例如 {@code group:eng#member}。 */
    record Userset(ObjectRef object, Rel relation) implements SubjectRef {}
}

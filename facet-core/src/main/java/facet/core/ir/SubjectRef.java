package facet.core.ir;

import java.util.Comparator;

/**
 * 元组的主体侧。
 *
 * <p>必须是 sealed 三元结构，而不是一个 String：{@link Userset} 是把"组成员"这类间接
 * 授权表达进元组的唯一办法（{@code doc:readme#viewer@group:eng#member}）。少了它，
 * RBAC 的角色和 ReBAC 的组继承就只能靠展开成具体用户，写放大会失控。
 *
 * <p>{@link Wildcard} 解决的是另一种写放大：{@code doc:readme#viewer@user:*} 表达
 * "所有登录用户都能看"。用组去模拟它要求注册流程把每个新用户加进组，一次漏加就是一个
 * 看不见的权限缺口，而且成员表会和用户表一样大。
 */
public sealed interface SubjectRef {

    /**
     * 确定的全序。
     *
     * <p>端口要求 {@code subjects()} 按此顺序返回，否则 explain 的分支顺序会随存储实现变化，
     * golden file 就只能对某一个适配器成立——而跨适配器一致性正是这套架构要保证的东西。
     * 排序键刻意与 SQL 的 {@code (subject_type, subject_id, subject_rel)} 对齐，
     * 因此 {@code Wildcard} 的 id 位取空串，与它在表里的编码一致。
     */
    Comparator<SubjectRef> ORDER = Comparator
            .comparing((SubjectRef s) -> switch (s) {
                case Principal(var type, _) -> type.name();
                case Userset(var object, _) -> object.type().name();
                case Wildcard(var type) -> type.name();
            })
            .thenComparing(s -> switch (s) {
                case Principal(_, var id) -> id;
                case Userset(var object, _) -> object.id();
                case Wildcard _ -> "";
            })
            .thenComparing(s -> switch (s) {
                case Principal _ -> "";
                case Userset(_, var relation) -> relation.name();
                case Wildcard _ -> "";
            });

    /** 线上表示通配主体的保留 id。{@code user:*} 是这一行业的通行写法。 */
    String WILDCARD_ID = "*";

    /** 具体主体：用户、服务账号。 */
    record Principal(ObjectType type, String id) implements SubjectRef {

        /**
         * 空 id 与保留 id 都在构造期拒绝。
         *
         * <p>空 id 会让这条元组把权限授给整个类型。这条检查现在有第二重意义：它保证空 id 在
         * 存储里是<strong>不可达状态</strong>，{@link Wildcard} 因此可以直接用
         * {@code subject_id = ''} 编码，不必加列、不必迁移。
         *
         * <p>{@code *} 被拒绝是另一件事：它是通配在<strong>线上格式</strong>里的写法
         * （{@code user:*}）。允许它做具体 id，wire 上就再也分不清"所有用户"和"id 是星号的
         * 那个用户"——一个授权 API 不该有这种歧义。
         */
        public Principal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("主体 id 不能为空；要授给整个类型请用 SubjectRef.Wildcard");
            }
            if (WILDCARD_ID.equals(id)) {
                throw new IllegalArgumentException(
                        "主体 id 不能是 \"*\"：它是通配主体在线上格式里的保留写法，请用 SubjectRef.Wildcard");
            }
        }
    }

    /** 主体集合：某对象上某关系的全部主体，例如 {@code group:eng#member}。 */
    record Userset(ObjectRef object, Rel relation) implements SubjectRef {}

    /**
     * 通配主体：某个类型的<strong>任意</strong>主体，例如 {@code user:*}。
     *
     * <p>按类型而不是一个全局的"公开"单例：多主体类型的部署需要区分"所有 user"和
     * "所有 service account"，一个不带类型的单例表达不了。schema 里只声明一个主体类型时，
     * 它自然退化成"公开"。
     *
     * <p><strong>它是一个开放集合，这带来两处不对称</strong>：
     * <ul>
     *   <li>check 与反查不受影响——两者都是针对某个具体主体求值，通配只是多一次匹配；</li>
     *   <li>展开无法把它落成具体主体，所以展开结果必须单独把它报出来，
     *       见 {@code Expander.Subjects}。</li>
     * </ul>
     */
    record Wildcard(ObjectType type) implements SubjectRef {

        public Wildcard {
            if (type == null) {
                throw new IllegalArgumentException("通配主体必须指定类型");
            }
        }
    }
}

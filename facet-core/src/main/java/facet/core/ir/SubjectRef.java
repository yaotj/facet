package facet.core.ir;

/**
 * 元组的主体侧。
 *
 * <p>必须是 sealed 二元结构，而不是一个 String：{@link Userset} 是把"组成员"这类间接
 * 授权表达进元组的唯一办法（{@code doc:readme#viewer@group:eng#member}）。少了它，
 * RBAC 的角色和 ReBAC 的组继承就只能靠展开成具体用户，写放大会失控。
 */
public sealed interface SubjectRef {

    /** 具体主体：用户、服务账号。 */
    record Principal(ObjectType type, String id) implements SubjectRef {

        public Principal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("主体 id 不能为空");
            }
        }
    }

    /** 主体集合：某对象上某关系的全部主体，例如 {@code group:eng#member}。 */
    record Userset(ObjectRef object, Rel relation) implements SubjectRef {}
}

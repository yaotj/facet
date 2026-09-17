package facet.core.ir;

/** 对象类型（namespace）。schema 以此为键组织各权限的 {@link Perm} 定义。 */
public record ObjectType(String name) {

    public ObjectType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("对象类型不能为空");
        }
    }
}

package facet.core.ir;

/** 对象类型（namespace）。schema 以此为键组织各权限的 {@link Perm} 定义。 */
public record ObjectType(String name) {

    /** 类型名是 schema 的查找键，空名意味着这条权限定义永远解析不到，构造期拒绝比查询期返回空更好排查。 */
    public ObjectType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("对象类型不能为空");
        }
    }
}

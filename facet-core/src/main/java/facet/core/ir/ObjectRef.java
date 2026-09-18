package facet.core.ir;

/** 受保护对象的引用，例如 {@code doc:readme}。 */
public record ObjectRef(ObjectType type, String id) {

    /** 空 id 会让引用退化成"整个类型"，在权限系统里这是最危险的一种笔误，构造期直接拒绝。 */
    public ObjectRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("对象 id 不能为空");
        }
    }
}

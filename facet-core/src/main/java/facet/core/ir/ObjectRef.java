package facet.core.ir;

/** 受保护对象的引用，例如 {@code doc:readme}。 */
public record ObjectRef(ObjectType type, String id) {

    public ObjectRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("对象 id 不能为空");
        }
    }
}

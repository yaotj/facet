package facet.core.ir;

/**
 * 分页游标：上一页最后一个对象的排序键。
 *
 * <p>用键游标而不是 offset：反查结果集会因并发写入而变化，offset 分页会漏项和重项。
 */
public record Cursor(String token) {

    public static final Cursor START = new Cursor("");

    public Cursor {
        if (token == null) {
            throw new IllegalArgumentException("游标不能为 null，起始页用 Cursor.START");
        }
    }

    /** 排序键：类型名 + id，保证跨页稳定。 */
    public static String keyOf(ObjectRef ref) {
        return ref.type().name() + ':' + ref.id();
    }

    public static Cursor of(ObjectRef ref) {
        return new Cursor(keyOf(ref));
    }
}

package facet.core.ir;

/**
 * 分页游标：上一页最后一个对象的排序键。
 *
 * <p>用键游标而不是 offset：反查结果集会因并发写入而变化，offset 分页会漏项和重项。
 */
public record Cursor(String token) {

    /** 起始页。空 token 小于任何排序键，首页因此不需要在 SQL 里走特例分支。 */
    public static final Cursor START = new Cursor("");

    /** 拒绝 null 而不是当成起始页：调用方漏传时应当立刻失败，而不是静默地把一次续页扫成首页。 */
    public Cursor {
        if (token == null) {
            throw new IllegalArgumentException("游标不能为 null，起始页用 Cursor.START");
        }
    }

    /** 排序键：类型名 + id，保证跨页稳定。 */
    public static String keyOf(ObjectRef ref) {
        return ref.type().name() + ':' + ref.id();
    }

    /**
     * 主体的排序键。
     *
     * <p>与 {@link #keyOf(ObjectRef)} 同构：{@code Principal} 也是"类型 + id"，复用同一套编码，
     * 展开分页与反查分页的游标格式才是同一个东西。{@code Userset} 没有对应形式——
     * 展开的结果一律是具体主体，不会有 userset。
     */
    public static String keyOf(SubjectRef.Principal principal) {
        return principal.type().name() + ':' + principal.id();
    }

    /** 由本页最后一个对象生成下一页的游标；调用方不应自己拼 token，编码规则属于内核实现细节。 */
    public static Cursor of(ObjectRef ref) {
        return new Cursor(keyOf(ref));
    }

    /** 由本页最后一个主体生成下一页的游标。 */
    public static Cursor of(SubjectRef.Principal principal) {
        return new Cursor(keyOf(principal));
    }
}

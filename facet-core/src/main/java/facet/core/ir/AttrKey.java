package facet.core.ir;

/**
 * 属性键。
 *
 * <p>两个正交的分级，都是必需的：
 * <ul>
 *   <li>{@link Tier} 决定该属性允许出现在哪些判定路径上。不设这条线，一条 ABAC 规则就能
 *       静默毁掉关系图的可反查性——而且要等到某个列表接口开始超时才会被发现。</li>
 *   <li>{@link Kind} 决定比较语义。{@code Cond} 的字面量是裸 {@code Object}，而属性落库
 *       只能选一种表示（文本）。没有类型信息时，"大于"在内存里是字典序、在 SQL 里是数值序，
 *       check 与反查会给出不同答案。类型必须在 schema 里声明，不能靠值去猜。</li>
 * </ul>
 */
public record AttrKey(String name, Kind kind, Tier tier) {

    public AttrKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("属性名不能为空");
        }
    }

    /** 便利构造：字符串属性是最常见的一类。 */
    public static AttrKey text(String name, Tier tier) {
        return new AttrKey(name, Kind.STRING, tier);
    }

    public static AttrKey number(String name, Tier tier) {
        return new AttrKey(name, Kind.NUMBER, tier);
    }

    public static AttrKey bool(String name, Tier tier) {
        return new AttrKey(name, Kind.BOOL, tier);
    }

    /**
     * 值的类型。
     *
     * <p>刻意只有三种：字符串、任意精度数值、布尔。再多就要在每个适配器上重复实现一套
     * 类型系统，而权限条件从来不需要那种表达力。
     */
    public enum Kind {

        /** 比较按码元序（对齐 Java 的 {@code String.compareTo} 与 SQL 的 {@code COLLATE "C"}）。 */
        STRING,

        /** 比较按数值（任意精度，避免 double 的相等陷阱）。 */
        NUMBER,

        /** 只允许 EQ / NE。 */
        BOOL
    }

    public enum Tier {

        /** 请求上下文自带，无 IO：时间、来源 IP、MFA 状态、client_id。 */
        CONTEXT,

        /** 随元组一起落库的资源快照属性，可建索引，可参与反查。 */
        SNAPSHOT,

        /**
         * 需要外部拉取（调 HR 系统问是否在职之类）。
         *
         * <p>有 IO、不可索引，因此<strong>禁止</strong>出现在可反查路径（反查资源、
         * 部分求值）。只允许作为 check 的最后一跳做后置过滤，并且要批量化。
         * 这条规则在 schema 加载期强制，不留到查询期。
         */
        EXTERNAL
    }
}

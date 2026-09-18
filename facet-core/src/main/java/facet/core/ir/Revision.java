package facet.core.ir;

/**
 * 一致性坐标（Zanzibar 的 zookie）。
 *
 * <p>它放在请求上下文里而不是 {@code TupleSource} 的方法签名上，是为了让"写后一致读"
 * 成为默认而非可选：签名上的参数总会有人传 null，上下文里的字段没法省略。
 */
public record Revision(long value) {

    /** 最新版本。 */
    public static final Revision HEAD = new Revision(-1L);

    /** 只接受 {@code -1}（{@link #HEAD}）与非负版本：更小的值一定来自计算错误，不能兜底成"读最新"。 */
    public Revision {
        if (value < -1L) {
            throw new IllegalArgumentException("非法版本坐标: " + value);
        }
    }

    /** 是否为 {@link #HEAD}。适配器据此跳过版本可见性过滤，走不带快照条件的快路径。 */
    public boolean isHead() {
        return value == -1L;
    }
}

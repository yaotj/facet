package facet.core.eval;

/**
 * 求值路径：从根到当前节点的 (perm, obj) 链。
 *
 * <p>持久化链表而不是可变集合，是为了在并行扇出下仍然正确：每个分支拿到的是自己的路径，
 * 兄弟分支互不可见。共享一个 "in progress" 集合会把并行求值的同一个 key 误报成环。
 */
public final class Trail {

    /** 顶层判定的起点。可以共享单例，因为链表节点全程不可变。 */
    public static final Trail EMPTY = new Trail(null, null);

    private final Memo.Key key;
    private final Trail parent;

    private Trail(Memo.Key key, Trail parent) {
        this.key = key;
        this.parent = parent;
    }

    /** 只向下延长、不改动既有节点，所以并行扇出的兄弟分支可以安全地共用同一个前缀。 */
    public Trail push(Memo.Key next) {
        return new Trail(next, this);
    }

    /** 环检测。线性扫描是有意的：路径长度被 {@code maxDepth} 压在几十的量级，建哈希集合的分配更贵。 */
    public boolean contains(Memo.Key candidate) {
        for (var node = this; node.key != null; node = node.parent) {
            if (node.key.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 已入栈的<strong>求值节点数</strong>，不是 schema 的层级数——{@code maxDepth} 比较的就是这个值。 */
    public int depth() {
        int n = 0;
        for (var node = this; node.key != null; node = node.parent) {
            n++;
        }
        return n;
    }
}

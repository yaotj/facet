package facet.core.eval;

/**
 * 求值路径：从根到当前节点的 (perm, obj) 链。
 *
 * <p>持久化链表而不是可变集合，是为了在并行扇出下仍然正确：每个分支拿到的是自己的路径，
 * 兄弟分支互不可见。共享一个 "in progress" 集合会把并行求值的同一个 key 误报成环。
 */
public final class Trail {

    public static final Trail EMPTY = new Trail(null, null);

    private final Memo.Key key;
    private final Trail parent;

    private Trail(Memo.Key key, Trail parent) {
        this.key = key;
        this.parent = parent;
    }

    public Trail push(Memo.Key next) {
        return new Trail(next, this);
    }

    public boolean contains(Memo.Key candidate) {
        for (var node = this; node.key != null; node = node.parent) {
            if (node.key.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public int depth() {
        int n = 0;
        for (var node = this; node.key != null; node = node.parent) {
            n++;
        }
        return n;
    }
}

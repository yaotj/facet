package facet.core.eval;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 求值路径：从根到当前节点的 (perm, obj) 链，外加整棵遍历共用的一份工作预算。
 *
 * <p>持久化链表而不是可变集合，是为了在并行扇出下仍然正确：每个分支拿到的是自己的路径，
 * 兄弟分支互不可见。共享一个 "in progress" 集合会把并行求值的同一个 key 误报成环。
 *
 * <p><strong>为什么预算挂在这里。</strong>{@code maxDepth} 与 {@code maxFanout} 都是
 * <em>单点</em>上限——前者限一条路径的长度，后者限一个算子的宽度——两者都不限制整棵树的大小。
 * 而预算的作用域恰好就是"这一次遍历"，也就是 Trail 的根所界定的范围；挂在这里，
 * 每个已经拿到 Trail 的地方都能扣费，不必给七个算子分支再加一个参数。
 */
public final class Trail {

    private final Memo.Key key;
    private final Trail parent;
    /** 整棵遍历共用一个计数器；并行扇出下会被多个线程同时扣减，所以用原子类型。 */
    private final AtomicInteger remaining;

    private Trail(Memo.Key key, Trail parent, AtomicInteger remaining) {
        this.key = key;
        this.parent = parent;
        this.remaining = remaining;
    }

    /**
     * 一次遍历的起点。
     *
     * @param maxNodes 这次遍历允许求值的节点总数，见 {@link #charge()}
     */
    public static Trail root(int maxNodes) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("工作预算必须为正");
        }
        return new Trail(null, null, new AtomicInteger(maxNodes));
    }

    /** 只向下延长、不改动既有节点，所以并行扇出的兄弟分支可以安全地共用同一个前缀与同一份预算。 */
    public Trail push(Memo.Key next) {
        return new Trail(next, this, remaining);
    }

    /**
     * 扣一个节点的预算。
     *
     * <p><strong>超预算必须抛而不是判 deny。</strong>它和 {@code DepthExceeded} 不同：后者是
     * 一条路径走到尽头，别的路径仍然有意义；预算耗尽说明这次判定的答案<em>不知道</em>，
     * 落成 deny 就是把一次资源不足读成"确实无权限"。
     *
     * <p>为什么需要它：记忆化对带剪枝的判定是失效的（见 {@link Memo#put}），而一旦数据深到
     * 真能撞上 {@code maxDepth}，根附近那些复用率最高的节点会全部进不了缓存，解释器就从
     * "每个节点算一次"退化成"枚举每条路径"。那时 {@code maxDepth} 只防住了栈溢出，
     * 挡不住一次请求把存储打穿——这个计数器挡的就是它。
     */
    public void charge() {
        if (remaining.decrementAndGet() < 0) {
            throw new EvalException("这次判定求值的节点数超过预算，"
                    + "通常是授权图在这个方向上过深或过宽；调 Ctx.Request.withMaxNodes 或收窄 schema");
        }
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

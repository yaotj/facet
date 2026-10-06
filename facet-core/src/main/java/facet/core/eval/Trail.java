package facet.core.eval;

import java.util.concurrent.atomic.AtomicInteger;

import facet.core.runtime.DeadlineExceededException;
import facet.core.runtime.EvalException;
import facet.core.runtime.Deadline;
import facet.core.runtime.Memo;

/**
 * 求值路径：从根到当前节点的 (perm, obj) 链，外加整棵遍历共用的工作预算与墙钟期限。
 *
 * <p>持久化链表而不是可变集合，是为了在并行扇出下仍然正确：每个分支拿到的是自己的路径，
 * 兄弟分支互不可见。共享一个 "in progress" 集合会把并行求值的同一个 key 误报成环。
 *
 * <p><strong>为什么两个上限都挂在这里。</strong>{@code maxDepth} 与 {@code maxFanout} 都是
 * <em>单点</em>上限——前者限一条路径的长度，后者限一个算子的宽度——两者都不限制整棵树的大小，
 * 也都不限时间。而预算与期限的作用域恰好就是"这一次遍历"，也就是 Trail 的根所界定的范围；
 * 挂在这里，每个已经拿到 Trail 的地方都能检查，不必给七个算子分支各加一个参数。
 */
public final class Trail {

    private final Memo.Key key;
    private final Trail parent;
    /** 整棵遍历共用一个计数器；并行扇出下会被多个线程同时扣减，所以用原子类型。 */
    private final AtomicInteger remaining;
    /** 期限本身不可变（存的是绝对时刻），所以可以直接共享。 */
    private final Deadline deadline;

    private Trail(Memo.Key key, Trail parent, AtomicInteger remaining, Deadline deadline) {
        this.key = key;
        this.parent = parent;
        this.remaining = remaining;
        this.deadline = deadline;
    }

    /**
     * 一次遍历的起点。
     *
     * @param maxNodes 这次遍历允许求值的节点总数，见 {@link #charge()}
     * @param deadline 墙钟期限；无期限传 {@link Deadline#NONE}
     */
    public static Trail root(int maxNodes, Deadline deadline) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("工作预算必须为正");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("不设期限用 Deadline.NONE，不用 null");
        }
        return new Trail(null, null, new AtomicInteger(maxNodes), deadline);
    }

    /** 只向下延长、不改动既有节点，所以并行扇出的兄弟分支可以安全地共用同一个前缀与同一份预算。 */
    public Trail push(Memo.Key next) {
        return new Trail(next, this, remaining, deadline);
    }

    /**
     * 扣一个节点的预算，并顺手检查期限。
     *
     * <p><strong>两者超限都必须抛而不是判 deny。</strong>它们和 {@code DepthExceeded} 不同：
     * 后者是一条路径走到尽头，别的路径仍然有意义；预算耗尽或期限到了说明这次判定的答案
     * <em>不知道</em>，落成 deny 就是把一次资源不足读成"确实无权限"。
     *
     * <p>为什么需要预算：记忆化对带剪枝的判定是失效的（见 {@link Memo#put}），而一旦数据深到
     * 真能撞上 {@code maxDepth}，根附近那些复用率最高的节点会全部进不了缓存，解释器就从
     * "每个节点算一次"退化成"枚举每条路径"。那时 {@code maxDepth} 只防住了栈溢出，
     * 挡不住一次请求把存储打穿——这个计数器挡的就是它。
     *
     * <p>为什么还需要期限：预算限的是工作量，而工作量合规、只是存储今天慢十倍的情况下，
     * 调用方一样等不起。两件事互不替代。
     *
     * <p>检查点落在节点之间，所以它保证的是"不再<em>发起</em>新的存储调用"。已经在途的那一次
     * 由适配器用 {@link Deadline#clamp} 自己收紧——期限只有传导下去才真的生效。
     */
    public void charge() {
        if (deadline.expired()) {
            throw new DeadlineExceededException(
                    "这次请求超过墙钟期限；已求值的部分作废，结论是未知而不是 deny");
        }
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

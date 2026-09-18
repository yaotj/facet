package facet.core.eval;

import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.spi.Metrics;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 请求上下文。
 *
 * <p>为什么用 {@link ScopedValue} 而不是参数透传、也不是 {@code ThreadLocal}：
 * <ul>
 *   <li>递归求值有六个算子分支，上下文塞进签名会污染整个内核 API；</li>
 *   <li>不可变、没有 {@code remove} 泄漏，并且自动继承到扇出的子任务；</li>
 *   <li>虚拟线程下"每 check 一个线程"，{@code ThreadLocal} 的内存开销在这个量级上是真问题。</li>
 * </ul>
 */
public final class Ctx {

    /** 公开而非私有：绑定检查与子作用域内的重新绑定发生在内核之外（并行扇出实现），这个 key 是契约的一部分。 */
    public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

    /** 递归深度上限。Through 的深度由数据决定，必须有个不依赖数据正确性的兜底。 */
    public static final int DEFAULT_MAX_DEPTH = 32;

    /**
     * 单次判定的工作预算（求值节点总数）。
     *
     * <p>取 20000 的依据：正常形状下一次判定走几十个节点，深目录加大组也就几百；到四位数
     * 就已经说明授权图在这个方向上不对了。留出两个数量级的余量，是为了不让预算变成
     * 一个需要每个部署都去调的参数——它是兜底，不是调优旋钮。
     */
    public static final int DEFAULT_MAX_NODES = 20_000;

    private Ctx() {
    }

    /** 未绑定就抛，不回落到默认上下文：默认主体会让越权判定看起来像一次正常的 deny。 */
    public static Request current() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("求值必须在 Ctx.run 内进行——上下文未绑定");
        }
        return CURRENT.get();
    }

    /**
     * 当前请求的期限，上下文未绑定时返回 {@link Deadline#NONE}。
     *
     * <p>给适配器用。与 {@link #current()} 的区别是它<strong>不要求</strong>上下文已绑定：
     * 建表、回收历史这类运维操作不在任何请求里，它们同样要创建语句，而那时去要一个
     * 请求上下文只会让运维路径必须包一层假的 {@code Ctx.run}。
     */
    public static Deadline deadline() {
        return CURRENT.isBound() ? CURRENT.get().deadline() : Deadline.NONE;
    }

    /**
     * 所有判定入口的唯一门。绑定的寿命就是这次调用，退出即失效，没有 {@code ThreadLocal} 那种需要清理的残留。
     *
     * @param body 求值体；扇出出去的子任务自动继承这份绑定，不必手工传递
     */
    public static <T> T run(Request request, Supplier<T> body) {
        return ScopedValue.where(CURRENT, request).call(body::get);
    }

    /**
     * 一次判定请求的全部上下文。
     *
     * @param principal    判定主体。整条递归路径共用同一个，中途不换身份，否则判定树读起来没有意义
     * @param at           一致性坐标。一次判定内所有元组读取必须落在同一个 {@link Revision} 上，
     *                     否则判定树是两个版本的拼接
     * @param contextAttrs 请求级属性（{@code AttrKey.Tier.CONTEXT}），入口一次给全；求值期不再回源
     * @param memo         记忆化的作用域就是这次请求。挂在长生命周期的 {@link Checker} 上会变成跨请求缓存，
     *                     读到已经失效的判定
     * @param maxDepth     递归节点数上限，语义见 {@link #withMaxDepth(int)}
     * @param maxNodes     单次判定的求值节点总数上限，语义见 {@link #withMaxNodes(int)}
     * @param deadline     整个请求的墙钟期限，语义见 {@link #withDeadline(java.time.Duration)}
     */
    public record Request(SubjectRef principal,
                          Revision at,
                          Map<String, Object> contextAttrs,
                          Memo memo,
                          int maxDepth,
                          int maxNodes,
                          Deadline deadline,
                          Metrics metrics) {

        public Request {
            contextAttrs = Map.copyOf(contextAttrs);
            if (maxDepth <= 0) {
                throw new IllegalArgumentException("深度上限必须为正");
            }
            if (maxNodes <= 0) {
                throw new IllegalArgumentException("工作预算必须为正");
            }
            if (deadline == null) {
                throw new IllegalArgumentException("不设期限用 Deadline.NONE，不用 null");
            }
            if (metrics == null) {
                throw new IllegalArgumentException("观测挂点用 Metrics.NOOP 表达关闭，不用 null");
            }
        }

        /** 默认请求：读最新、无上下文属性、无期限、不观测。 */
        public static Request of(SubjectRef principal) {
            return new Request(principal, Revision.HEAD, Map.of(), new Memo(),
                    DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES, Deadline.NONE, Metrics.NOOP);
        }

        public Request withContextAttrs(Map<String, Object> attrs) {
            return new Request(principal, at, attrs, memo, maxDepth, maxNodes, deadline, metrics);
        }

        public Request at(Revision revision) {
            return new Request(principal, revision, contextAttrs, memo, maxDepth, maxNodes,
                    deadline, metrics);
        }

        /**
         * 装上观测挂点。
         *
         * <p>走上下文而不是构造器参数：求值器已经有四个协作对象，再加一个会让每个调用点
         * 都要关心观测；而观测恰恰是请求作用域的横切关注点，{@code ScopedValue} 就是为它准备的。
         */
        public Request withMetrics(Metrics sink) {
            return new Request(principal, at, contextAttrs, memo, maxDepth, maxNodes,
                    deadline, sink);
        }

        /**
         * 抬高递归深度上限。
         *
         * <p>注意单位是<strong>求值节点数</strong>，不是层级数。一层 folder 继承会压掉
         * 三到四个节点（{@code AnyOf} → {@code Through} → {@code Ref} → 下一层的 {@code AnyOf}），
         * 所以默认的 32 只够走七八层。层级可能更深的部署必须显式抬高，否则症状是
         * "深目录下的文档突然没权限"，而 explain 里只有一行 {@code DEPTH-EXCEEDED}。
         */
        public Request withMaxDepth(int depth) {
            return new Request(principal, at, contextAttrs, memo, depth, maxNodes,
                    deadline, metrics);
        }

        /**
         * 调整单次判定的工作预算。
         *
         * <p>它与 {@link #withMaxDepth(int)} 管的是两件不同的事：深度限一条<em>路径</em>的长度，
         * 预算限整棵<em>树</em>的大小。深度与扇出双双合规、但图足够宽的情况下，一次判定仍然可以
         * 访问几十万个节点——每个节点在真实存储上是一次往返。
         *
         * <p>抬高它之前先确认那是业务上真实存在的形状：绝大多数情况下，需要抬高预算说明
         * schema 的某条路径应该改成可反查的关系，而不是让 check 每次去遍历半张图。
         */
        public Request withMaxNodes(int nodes) {
            return new Request(principal, at, contextAttrs, memo, maxDepth, nodes,
                    deadline, metrics);
        }

        /**
         * 给整个请求设一个墙钟期限。
         *
         * <p>期限从<strong>调用这个方法的时刻</strong>开始算，并且覆盖整个请求——
         * {@code checkAll} 的一整批共用同一个期限，而不是每个对象各拿一份。这正是它与
         * {@link #withMaxNodes(int)} 的分工：预算按判定算工作量，期限按请求算时间。
         *
         * <p>它与预算是互补的，不是二选一。见 {@link Deadline} 里的对比。
         */
        public Request withDeadline(java.time.Duration budget) {
            return new Request(principal, at, contextAttrs, memo, maxDepth, maxNodes,
                    Deadline.after(budget), metrics);
        }
    }
}

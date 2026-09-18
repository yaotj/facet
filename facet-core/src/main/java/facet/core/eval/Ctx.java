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
     */
    public record Request(SubjectRef principal,
                          Revision at,
                          Map<String, Object> contextAttrs,
                          Memo memo,
                          int maxDepth,
                          Metrics metrics) {

        public Request {
            contextAttrs = Map.copyOf(contextAttrs);
            if (maxDepth <= 0) {
                throw new IllegalArgumentException("深度上限必须为正");
            }
            if (metrics == null) {
                throw new IllegalArgumentException("观测挂点用 Metrics.NOOP 表达关闭，不用 null");
            }
        }

        /** 默认请求：读最新、无上下文属性、不观测。 */
        public static Request of(SubjectRef principal) {
            return new Request(principal, Revision.HEAD, Map.of(), new Memo(),
                    DEFAULT_MAX_DEPTH, Metrics.NOOP);
        }

        public Request withContextAttrs(Map<String, Object> attrs) {
            return new Request(principal, at, attrs, memo, maxDepth, metrics);
        }

        public Request at(Revision revision) {
            return new Request(principal, revision, contextAttrs, memo, maxDepth, metrics);
        }

        /**
         * 装上观测挂点。
         *
         * <p>走上下文而不是构造器参数：求值器已经有四个协作对象，再加一个会让每个调用点
         * 都要关心观测；而观测恰恰是请求作用域的横切关注点，{@code ScopedValue} 就是为它准备的。
         */
        public Request withMetrics(Metrics sink) {
            return new Request(principal, at, contextAttrs, memo, maxDepth, sink);
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
            return new Request(principal, at, contextAttrs, memo, depth, metrics);
        }
    }
}

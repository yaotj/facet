package facet.core.eval;

import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;

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

    public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

    /** 递归深度上限。Through 的深度由数据决定，必须有个不依赖数据正确性的兜底。 */
    public static final int DEFAULT_MAX_DEPTH = 32;

    private Ctx() {
    }

    public static Request current() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("求值必须在 Ctx.run 内进行——上下文未绑定");
        }
        return CURRENT.get();
    }

    public static <T> T run(Request request, Supplier<T> body) {
        return ScopedValue.where(CURRENT, request).call(body::get);
    }

    public record Request(SubjectRef principal,
                          Revision at,
                          Map<String, Object> contextAttrs,
                          Memo memo,
                          int maxDepth) {

        public Request {
            contextAttrs = Map.copyOf(contextAttrs);
            if (maxDepth <= 0) {
                throw new IllegalArgumentException("深度上限必须为正");
            }
        }

        public static Request of(SubjectRef principal) {
            return new Request(principal, Revision.HEAD, Map.of(), new Memo(), DEFAULT_MAX_DEPTH);
        }

        public Request withContextAttrs(Map<String, Object> attrs) {
            return new Request(principal, at, attrs, memo, maxDepth);
        }

        public Request at(Revision revision) {
            return new Request(principal, revision, contextAttrs, memo, maxDepth);
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
            return new Request(principal, at, contextAttrs, memo, depth);
        }
    }
}

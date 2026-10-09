package facet.sdk;

import facet.core.eval.Checker;
import facet.core.eval.Expander;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Ctx;
import facet.core.runtime.Decision;
import facet.core.runtime.Explain;
import facet.core.schema.Schema;
import facet.core.schema.Validator;
import facet.core.spi.AttrSource;
import facet.core.spi.DecisionCache;
import facet.core.spi.DecisionCachePolicy;
import facet.core.spi.Metrics;
import facet.core.spi.PlanExecutor;
import facet.core.spi.RevisionSource;
import facet.core.spi.TupleSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * 进程内判定门面。
 *
 * <p>把内核那套"自己装求值器、自己包 {@code Ctx.run}、自己管主体与坐标"从业务代码里收掉——调用方
 * 只拿到四个贴合使用场景的方法：单点判定、批量判定、反查资源、展开主体。其余的（{@code ScopedValue}
 * 绑定、工作预算、递归深度、墙钟期限、观测挂点）都在门面里默认好，需要时才覆盖。
 *
 * <p>这是 Facet 的<strong>驱动侧</strong>第二种形态：{@code facet-pdp-http} 把内核包成 HTTP 服务，
 * 本类把内核包成进程内库。两者共享同一份内核，差别只在"请求从哪来"——这也是 README 自己承认的、
 * "只有真实接入才暴露"的 ergonomics 问题最直接的回应：接进应用不再需要手写 {@code Ctx.run}。
 *
 * <p>本门面<strong>可选接判定缓存</strong>：用 {@code Builder.withCache} / {@code withStaleness} 注入
 * {@code facet.core.spi} 里的 {@code DecisionCache} / {@code RevisionSource}。键计算与坐标陈旧语义与 HTTP
 * 服务共用同一个 {@code DecisionCachePolicy}，不再各写一套。缓存命中返回的结论不含完整判定树
 * （与 PDP 行为一致：判定树不缓存，且体积大、易失）；不带具体坐标且不配陈旧窗口的请求不进缓存。
 */
public final class Facet {

    private final Schema schema;
    private final Checker checker;
    private final Planner planner;
    private final Expander expander;
    /** 反查需要；单点/批量判定不需要。 */
    private final PlanExecutor executor;
    private final Metrics metrics;
    private final int maxNodes;
    private final int maxDepth;
    private final Duration deadline;
    /** 判定缓存（默认 {@link DecisionCache#NONE}）。与坐标陈旧策略共用同一个 {@link DecisionCachePolicy}。 */
    private final DecisionCache cache;
    private final DecisionCachePolicy cachePolicy;

    private Facet(Builder b) {
        this.schema = b.schema;
        // 装库即校验，而不是等第一次判定才炸——schema 不合法会让整条判定路径静默答 deny
        Validator.validate(schema);
        this.checker = new Checker(schema, b.tuples, b.attrs);
        this.planner = new Planner(schema, b.tuples.caps());
        this.expander = new Expander(schema, b.tuples, b.attrs);
        this.executor = b.executor;
        this.metrics = b.metrics;
        this.maxNodes = b.maxNodes;
        this.maxDepth = b.maxDepth;
        this.deadline = b.deadline;
        this.cache = b.cache;
        this.cachePolicy = new DecisionCachePolicy(b.cache, b.revisions, b.staleness);
    }

    /** 单点判定：{@code subject} 能不能对 {@code object} 做 {@code relation}（读最新）。 */
    public Decision check(SubjectRef subject, ObjectRef object, Rel relation) {
        return checkAt(subject, object, relation, Revision.HEAD);
    }

    /**
     * 单点判定：在给定坐标 {@code at} 上求值。
     *
     * <p>若装了缓存，带具体坐标（或钉到陈旧坐标的 HEAD）的请求会先查缓存；命中直接返回结论，
     * 不重算。缓存命中返回的结论不含完整判定树（与 PDP 行为一致：判定树不缓存）。
     */
    public Decision checkAt(SubjectRef subject, ObjectRef object, Rel relation, Revision at) {
        var resolved = cachePolicy.resolveAt(at.isHead() ? null : at.value());
        var key = cachePolicy.cacheKey(subject, object, relation, resolved, null, false);
        if (key != null) {
            var hit = cache.get(key);
            if (hit != null) {
                return cached(hit, relation, object);
            }
        }
        var decision = Ctx.run(request(subject, resolved),
                () -> checker.check(object, relation));
        if (key != null) {
            cache.put(key, decision.allowed());
        }
        return decision;
    }

    /**
     * 批量判定：同一主体对一批对象做同一个关系。
     *
     * <p>整批跑在同一次 {@code Ctx.run} 里，共享 {@code Memo}、属性也只预取一次。结果保持入参顺序。
     */
    public SequencedMap<ObjectRef, Decision> checkAll(
            SubjectRef subject, Collection<ObjectRef> objects, Rel relation) {
        return checkAllAt(subject, objects, relation, Revision.HEAD);
    }

    /**
     * 批量判定：在给定坐标 {@code at} 上求值。整批共享一次 {@code Ctx}，结果与入参同序。
     *
     * <p>带具体坐标的请求先查缓存，命中的那部分连属性预取都省掉；未命中的仍共享同一次求值。
     */
    public SequencedMap<ObjectRef, Decision> checkAllAt(
            SubjectRef subject, Collection<ObjectRef> objects, Rel relation, Revision at) {
        var resolved = cachePolicy.resolveAt(at.isHead() ? null : at.value());
        var answers = new LinkedHashMap<ObjectRef, Decision>();
        var pending = new ArrayList<ObjectRef>();
        for (var object : objects) {
            var key = cachePolicy.cacheKey(subject, object, relation, resolved, null, false);
            var hit = key == null ? null : cache.get(key);
            if (hit != null) {
                answers.put(object, cached(hit, relation, object));
            } else {
                pending.add(object);
            }
        }
        if (!pending.isEmpty()) {
            var decisions = Ctx.run(request(subject, resolved),
                    () -> checker.checkAll(pending, relation));
            for (var object : pending) {
                var decision = decisions.get(object);
                answers.put(object, decision);
                var key = cachePolicy.cacheKey(subject, object, relation, resolved, null, false);
                if (key != null) {
                    cache.put(key, decision.allowed());
                }
            }
        }
        return answers;
    }

    /**
     * 反查：{@code subject} 能碰哪些 {@code objectType} 类型的资源做 {@code relation}（首页）。
     *
     * @throws IllegalStateException 若装配时没给 {@link Builder#executor}
     */
    public List<ObjectRef> lookup(SubjectRef subject, ObjectType objectType, Rel relation, int limit) {
        return lookup(subject, objectType, relation, Cursor.START, limit);
    }

    public List<ObjectRef> lookup(
            SubjectRef subject, ObjectType objectType, Rel relation, Cursor after, int limit) {
        if (executor == null) {
            throw new IllegalStateException("反查需要 PlanExecutor；用 Facet.builder(schema).executor(...) 装上");
        }
        var plan = planner.plan(objectType, relation, after, limit);
        return Ctx.run(request(subject, Revision.HEAD),
                () -> executor.execute(plan).toList());
    }

    /** 展开：谁（具体主体 + 通配类型）能对 {@code object} 做 {@code relation}（首页）。 */
    public Expander.Subjects whoCan(ObjectRef object, Rel relation, int limit) {
        return whoCan(object, relation, Cursor.START, limit);
    }

    public Expander.Subjects whoCan(ObjectRef object, Rel relation, Cursor after, int limit) {
        // 展开不针对某个主体：Ctx 里的 principal 只是占位（解释器为 check 定的形状）。
        // 用固定 id 而不是对象的 id：后者若恰好是 "*"，Principal 构造器会拒绝。
        var placeholder = new SubjectRef.Principal(object.type(), "facet-expander");
        return Ctx.run(request(placeholder, Revision.HEAD),
                () -> expander.subjects(object, relation, after, limit));
    }

    /** 装配一个门面。 */
    public static Builder builder(Schema schema) {
        return new Builder(schema);
    }

    /** 缓存命中：结论为真，但判定树已丢（与 PDP 一致）。用叶子节点近似表达 allow / deny。 */
    private static Decision cached(boolean allowed, Rel relation, ObjectRef object) {
        return new Decision(allowed,
                allowed ? new Explain.Hit(relation, object) : new Explain.Miss(relation, object));
    }

    /** 按本次调用的主体与坐标，套上引擎级的默认值，造出一份请求上下文。 */
    private Ctx.Request request(SubjectRef principal, Revision at) {
        var req = Ctx.Request.of(principal).at(at).withMetrics(metrics);
        if (maxNodes != Ctx.DEFAULT_MAX_NODES) {
            req = req.withMaxNodes(maxNodes);
        }
        if (maxDepth != Ctx.DEFAULT_MAX_DEPTH) {
            req = req.withMaxDepth(maxDepth);
        }
        if (!deadline.isZero()) {
            req = req.withDeadline(deadline);
        }
        return req;
    }

    /** 装配门面：把存储适配器、属性源、可选的规划执行器与调优旋钮接进来。 */
    public static final class Builder {

        private final Schema schema;
        private TupleSource tuples;
        private AttrSource attrs;
        private PlanExecutor executor;
        private Metrics metrics = Metrics.NOOP;
        private int maxNodes = Ctx.DEFAULT_MAX_NODES;
        private int maxDepth = Ctx.DEFAULT_MAX_DEPTH;
        private Duration deadline = Duration.ZERO;
        private DecisionCache cache = DecisionCache.NONE;
        private RevisionSource revisions = RevisionSource.NONE;
        private Duration staleness = Duration.ZERO;

        private Builder(Schema schema) {
            if (schema == null) {
                throw new IllegalArgumentException("schema 不能为空");
            }
            this.schema = schema;
        }

        public Builder tuples(TupleSource source) {
            this.tuples = source;
            return this;
        }

        public Builder attrs(AttrSource source) {
            this.attrs = source;
            return this;
        }

        /** 反查端点需要；只做单点/批量判定时可以不装。 */
        public Builder executor(PlanExecutor source) {
            this.executor = source;
            return this;
        }

        public Builder metrics(Metrics sink) {
            this.metrics = sink;
            return this;
        }

        /** 单次判定的工作预算（求值节点总数）。默认 {@link Ctx#DEFAULT_MAX_NODES}。 */
        public Builder maxNodes(int nodes) {
            this.maxNodes = nodes;
            return this;
        }

        /** 递归深度上限。默认 {@link Ctx#DEFAULT_MAX_DEPTH}。 */
        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        /** 墙钟期限；不设为 {@link Duration#ZERO}（无期限）。 */
        public Builder deadline(Duration budget) {
            this.deadline = budget;
            return this;
        }

        /** 接上判定缓存：只有带具体坐标的请求会被缓存。默认不缓存。 */
        public Builder withCache(DecisionCache sink) {
            this.cache = sink;
            return this;
        }

        /**
         * 接受有界陈旧：读 HEAD 的请求因此也能进缓存（钉到略旧但新鲜的坐标上）。
         *
         * <p>代价是有界陈旧——窗内的新授权看不到、撤销仍生效。必须由调用方明确接受，且存储必须
         * 声明 {@code snapshotRead}（钉住坐标读需要快照读能力），否则 {@link #build()} 抛错。
         */
        public Builder withStaleness(RevisionSource source, Duration window) {
            this.revisions = source;
            this.staleness = window;
            return this;
        }

        public Facet build() {
            if (tuples == null) {
                throw new IllegalArgumentException("必须提供 TupleSource");
            }
            if (attrs == null) {
                throw new IllegalArgumentException("必须提供 AttrSource");
            }
            if (staleness.isNegative()) {
                throw new IllegalArgumentException("陈旧窗口不能为负");
            }
            if (!staleness.isZero() && !tuples.caps().snapshotRead()) {
                throw new IllegalArgumentException(
                        "配置了陈旧窗口，但存储未声明 snapshotRead：钉住坐标读需要快照读能力");
            }
            return new Facet(this);
        }
    }
}

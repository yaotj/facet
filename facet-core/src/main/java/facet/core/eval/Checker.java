package facet.core.eval;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.Fanout;
import facet.core.spi.TupleSource;
import facet.core.runtime.Ctx;
import facet.core.runtime.Decision;
import facet.core.runtime.Explain;
import facet.core.runtime.EvalException;
import facet.core.sem.Conds;
import facet.core.schema.Schema;
import facet.core.spi.decorators.PrefetchedAttrs;
import facet.core.runtime.Memo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.Callable;

/**
 * check 路径：<strong>解释器</strong>。
 *
 * <p>{@code Through} 的递归深度由数据决定（folder 层级有多深就走多深），编不成静态计划，
 * 所以这条路必须是解释器，并且必须自带记忆化、环检测、深度上限三件套。
 *
 * <p>它与 {@link Planner} 方向相反，不共用遍历代码。指望复用会写出一个两头不讨好的抽象。
 */
public final class Checker {

    private final Schema schema;
    private final TupleSource tuples;
    private final AttrSource attrs;
    private final Fanout fanout;

    /**
     * 端口在构造期注入并固定：求值中途换存储会让同一棵判定树横跨两个数据源。
     *
     * @param fanout 扇出策略。它同时决定并发连接数，须与 {@code tuples.caps().maxFanout()} 相称
     */
    public Checker(Schema schema, TupleSource tuples, AttrSource attrs, Fanout fanout) {
        this.schema = schema;
        this.tuples = tuples;
        this.attrs = attrs;
        this.fanout = fanout;
    }

    /** 默认串行：并行扇出要占额外的连接预算，只能由部署方显式选择，不该是不留意就生效的默认值。 */
    public Checker(Schema schema, TupleSource tuples, AttrSource attrs) {
        this(schema, tuples, attrs, Fanout.SEQUENTIAL);
    }

    /**
     * 单点判定入口。必须在 {@link Ctx#run} 之内调用——主体、版本、{@link Memo} 都从上下文取。
     *
     * <p>观测挂点落在这一层，因为只有这里知道关系名。
     */
    public Decision check(ObjectRef obj, Rel rel) {
        long started = System.nanoTime();
        var decision = check(schema.relation(obj.type(), rel).rewrite(), obj);
        Ctx.current().metrics().decision(rel, decision.allowed(), System.nanoTime() - started);
        return decision;
    }

    /**
     * 批量判定："这一批资源里我能做 {@code rel} 的有哪些"。
     *
     * <p>它不是 {@code check} 的语法糖，而是两处真实开销的解法：
     * <ul>
     *   <li><strong>属性预取。</strong>入口对象自身要用到的 SNAPSHOT / EXTERNAL 属性
     *       一次取回。逐个 check 的话，一次"这 200 个文档我能看哪些"就是 200 次外部调用。</li>
     *   <li><strong>记忆化共享。</strong>调用方在同一个 {@code Ctx.run} 里发起，整批共用一个
     *       {@code Memo}；同一个 folder 被 200 个 doc 指向时只求值一次。</li>
     * </ul>
     *
     * <p>预取只覆盖入口对象自身的属性（见 {@link Attrs#localKeys}）。{@code Through} 之后的
     * 对象由数据决定，那部分仍是逐条回源——这是已知边界。
     *
     * @return 保持入参顺序的判定结果
     */
    public SequencedMap<ObjectRef, Decision> checkAll(Collection<ObjectRef> objects, Rel rel) {
        var out = new LinkedHashMap<ObjectRef, Decision>();
        if (objects.isEmpty()) {
            return out;
        }
        var keys = new LinkedHashSet<AttrKey>();
        objects.stream().map(ObjectRef::type).distinct()
                .forEach(type -> keys.addAll(Attrs.localKeys(schema, type, rel)));

        // 没有属性要预取就不必套装饰器，省掉一次无谓的 map 构造
        var batch = this;
        if (!keys.isEmpty()) {
            // 上报批量大小：它一旦长期是 1，说明预取没生效——而结果完全正确，只是慢。
            // 埋点放这里而不是 PrefetchedAttrs：那是个工具类，不该隐含"必须在请求上下文内"。
            var metrics = Ctx.current().metrics();
            keys.forEach(key -> metrics.attributeBatch(key, objects.size()));
            batch = new Checker(schema, tuples, PrefetchedAttrs.of(attrs, keys, objects), fanout);
        }
        var evaluator = batch;
        objects.forEach(obj -> out.put(obj, evaluator.check(obj, rel)));
        return out;
    }

    /**
     * 对一个 {@link Perm} 直接求值，不走 schema 的关系查找。前端与测试要拿匿名表达式判定时用它。
     *
     * <p>从 {@link Trail#EMPTY} 起算，因此环检测与深度上限的作用域就是这一次调用。
     *
     * <p>受检异常在这里收口成 {@link EvalException}：让七个算子分支都挂上 {@code throws Exception}
     * 会把存储的实现细节泄进整个内核签名。{@code RuntimeException} 原样透出以保留原始栈。
     */
    public Decision check(Perm perm, ObjectRef obj) {
        var request = Ctx.current();
        try {
            return eval(perm, obj, Trail.root(request.maxNodes(), request.deadline()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("求值失败: " + e.getMessage(), e);
        }
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，这里立刻编译失败。 */
    private Decision eval(Perm perm, ObjectRef obj, Trail trail) throws Exception {
        // 先扣预算再做任何判断：剪枝返回与记忆化命中同样是一次访问，
        // 而指数退化的绝大部分开销恰恰就落在这些"便宜"的返回上
        trail.charge();
        var request = Ctx.current();
        var key = new Memo.Key(perm, obj);

        if (trail.contains(key)) {
            return Decision.deny(new Explain.CycleCut(obj));
        }
        if (trail.depth() >= request.maxDepth()) {
            return Decision.deny(new Explain.DepthExceeded(request.maxDepth()));
        }
        var cached = request.memo().get(key);
        if (cached != null) {
            return cached;
        }

        var path = trail.push(key);
        var decision = switch (perm) {
            case Perm.Direct(var rel) -> direct(rel, obj, path);
            case Perm.AnyOf(var terms) -> anyOf(terms, obj, path);
            case Perm.AllOf(var terms) -> allOf(terms, obj, path);
            case Perm.Minus(var base, var denied) -> minus(base, denied, obj, path);
            case Perm.Through(var hop, var then) -> through(hop, then, obj, path);
            case Perm.Guarded(var base, var cond) -> guarded(base, cond, obj, path);
            case Perm.Ref(var rel) -> ref(rel, obj, path);
        };
        request.memo().put(key, decision);
        return decision;
    }

    /**
     * 递归就在这一步：{@code Ref} 往 schema 查定义再继续求值。
     *
     * <p>深度由数据决定（folder 层级有多深就走多深），所以这条路只能是解释器；
     * 环检测与深度上限在 {@code eval} 的入口处，对每一层 {@code Ref} 都生效。
     */
    private Decision ref(Rel rel, ObjectRef obj, Trail trail) throws Exception {
        var inner = eval(schema.relation(obj.type(), rel).rewrite(), obj, trail);
        return new Decision(inner.allowed(),
                new Explain.Branch("Ref(" + rel.name() + ")", List.of(inner.explain())));
    }

    private Decision direct(Rel rel, ObjectRef obj, Trail trail) throws Exception {
        var subjects = tuples.subjects(obj, rel);
        var principal = Ctx.current().principal();
        // 通配授权只多一次集合查找，不多一次往返：check 是针对具体主体求值的，
        // user:* 命中与 user:alice 命中在这里是同一件事
        if (subjects.contains(principal) || subjects.contains(wildcardFor(principal))) {
            return Decision.allow(new Explain.Hit(rel, obj));
        }

        // Userset(o, r) 等价于"在 o 上求 Direct(r)"——组成员、角色继承都走这一步展开。
        var usersets = subjects.stream()
                .filter(SubjectRef.Userset.class::isInstance)
                .map(SubjectRef.Userset.class::cast)
                .toList();
        if (usersets.isEmpty()) {
            return Decision.deny(new Explain.Miss(rel, obj));
        }
        guardFanout(usersets.size(), "Userset 展开");

        var tasks = usersets.stream()
                .map(us -> (Callable<Decision>) () -> eval(new Perm.Direct(us.relation()), us.object(), trail))
                .toList();
        return summarize("Direct(" + rel.name() + ")", fanout.firstMatch(tasks, Decision::allowed));
    }

    private Decision anyOf(List<Perm> terms, ObjectRef obj, Trail trail) throws Exception {
        var tasks = terms.stream()
                .map(term -> (Callable<Decision>) () -> eval(term, obj, trail))
                .toList();
        return summarize("AnyOf", fanout.firstMatch(tasks, Decision::allowed));
    }

    private Decision allOf(List<Perm> terms, ObjectRef obj, Trail trail) throws Exception {
        var tasks = terms.stream()
                .map(term -> (Callable<Decision>) () -> eval(term, obj, trail))
                .toList();
        var results = fanout.all(tasks);
        return new Decision(results.stream().allMatch(Decision::allowed),
                new Explain.Branch("AllOf", results.stream().map(Decision::explain).toList()));
    }

    /** deny 单调：{@code denied} 成立即终局，上层任何算子不可恢复。 */
    private Decision minus(Perm base, Perm denied, ObjectRef obj, Trail trail) throws Exception {
        var results = fanout.all(List.<Callable<Decision>>of(
                () -> eval(base, obj, trail),
                () -> eval(denied, obj, trail)));
        var allow = results.get(0);
        var deny = results.get(1);
        if (deny.allowed()) {
            return Decision.deny(new Explain.DeniedBy(deny.explain()));
        }
        return new Decision(allow.allowed(),
                new Explain.Branch("Minus", List.of(allow.explain(), deny.explain())));
    }

    private Decision through(Rel hop, Perm then, ObjectRef obj, Trail trail) throws Exception {
        var targets = tuples.targets(obj, hop);
        if (targets.isEmpty()) {
            return Decision.deny(new Explain.Miss(hop, obj));
        }
        guardFanout(targets.size(), "Through(" + hop.name() + ")");

        var tasks = targets.stream()
                .map(target -> (Callable<Decision>) () -> eval(then, target, trail))
                .toList();
        return summarize("Through(" + hop.name() + ")", fanout.firstMatch(tasks, Decision::allowed));
    }

    /** 先算 base 再算 cond：条件可能带 IO，base 不成立就没必要付这个代价。 */
    private Decision guarded(Perm base, Cond cond, ObjectRef obj, Trail trail) throws Exception {
        var inner = eval(base, obj, trail);
        if (!inner.allowed()) {
            return Decision.deny(new Explain.Branch("Guarded", List.of(inner.explain())));
        }
        boolean ok = Conds.eval(cond, obj, attrs, Ctx.current().contextAttrs());
        return new Decision(ok, new Explain.Branch("Guarded",
                List.of(inner.explain(), new Explain.CondEval(cond, ok, Cond.tierOf(cond)))));
    }

    private Decision summarize(String op, List<Decision> results) {
        return new Decision(results.stream().anyMatch(Decision::allowed),
                new Explain.Branch(op, results.stream().map(Decision::explain).toList()));
    }

    /** {@code Caps.maxFanout} 是硬上限：宁可拒绝，也不要一个查询把存储打穿。 */
    /**
     * 主体对应的通配形态。
     *
     * <p>{@code Userset} 主体没有通配形态——{@code group:eng#member} 本身就是一个集合，
     * 再对它通配没有意义；只有具体主体才会问"这个类型是不是被整体授权了"。
     */
    private static SubjectRef wildcardFor(SubjectRef principal) {
        return switch (principal) {
            case SubjectRef.Principal(var type, _) -> new SubjectRef.Wildcard(type);
            case SubjectRef.Userset(var object, _) -> new SubjectRef.Wildcard(object.type());
            case SubjectRef.Wildcard wildcard -> wildcard;
        };
    }

    private void guardFanout(int width, String where) {
        // 先上报再判上限：撞上限之前的增长趋势才是能用来预警的信号
        Ctx.current().metrics().fanout(where, width);
        int limit = tuples.caps().maxFanout();
        if (width > limit) {
            throw new EvalException(where + " 扇出 " + width + " 超过存储声明的上限 " + limit);
        }
    }
}

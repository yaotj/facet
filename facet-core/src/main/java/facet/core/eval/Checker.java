package facet.core.eval;

import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.Fanout;
import facet.core.spi.TupleSource;

import java.util.List;
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

    public Checker(Schema schema, TupleSource tuples, AttrSource attrs, Fanout fanout) {
        this.schema = schema;
        this.tuples = tuples;
        this.attrs = attrs;
        this.fanout = fanout;
    }

    public Checker(Schema schema, TupleSource tuples, AttrSource attrs) {
        this(schema, tuples, attrs, Fanout.SEQUENTIAL);
    }

    public Decision check(ObjectRef obj, Rel rel) {
        return check(schema.relation(obj.type(), rel).rewrite(), obj);
    }

    public Decision check(Perm perm, ObjectRef obj) {
        try {
            return eval(perm, obj, Trail.EMPTY);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("求值失败: " + e.getMessage(), e);
        }
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，这里立刻编译失败。 */
    private Decision eval(Perm perm, ObjectRef obj, Trail trail) throws Exception {
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
        if (subjects.contains(Ctx.current().principal())) {
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
    private void guardFanout(int width, String where) {
        int limit = tuples.caps().maxFanout();
        if (width > limit) {
            throw new EvalException(where + " 扇出 " + width + " 超过存储声明的上限 " + limit);
        }
    }
}

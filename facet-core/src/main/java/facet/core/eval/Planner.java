package facet.core.eval;

import facet.core.ir.Cursor;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Plan;
import facet.core.ir.Rel;
import facet.core.spi.TupleSource;
import facet.core.schema.SchemaException;
import facet.core.runtime.EvalException;
import facet.core.schema.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 反查路径：<strong>编译器</strong>。
 *
 * <p>把 {@link Perm} 降级成 {@link Plan} 这套集合代数，交给适配器下推。与 {@link Checker}
 * 方向相反：{@code Through} 在 check 里是从对象往下问"我的 folder 是谁"，在这里是从主体
 * 命中的 folder 往上展开"这些 folder 下有哪些 doc"。
 *
 * <p>递归的处理是两条路差别最大的地方。check 是解释器，任意递归形状都能跑；反查必须编成
 * 一条查询，所以只支持<strong>同类型单跳自递归</strong>——{@code T#r = AnyOf[基础项…,
 * Through(hop, Ref(r))]} 且 {@code hop} 指回 {@code T}。folder 层级、组嵌套、组织树都是
 * 这个形状。其余形状（跨类型互递归、递归藏在 AllOf/Minus 之下）在编译期明确拒绝，
 * 而不是产出一个悄悄漏结果的计划。
 */
public final class Planner {

    private record Node(ObjectType type, Rel rel) {}

    private final Schema schema;
    private final TupleSource.Caps caps;

    /** {@code caps} 在构造期就定下：反向索引与递归查询能力决定编译结果，缺了要在编译期拒绝而不是执行时才发现。 */
    public Planner(Schema schema, TupleSource.Caps caps) {
        this.schema = schema;
        this.caps = caps;
    }

    /**
     * 编译 {@code type#rel} 的反查计划。
     *
     * <p>两道拒绝都放在编译期：存储没声明反向索引、schema 没声明 {@code listable}，都直接抛。
     * 产出一个会退化成全表扫的计划，等于把问题留给数据量增长去暴露。
     *
     * @param after 上一页游标。分页由适配器执行，但包进 {@code Plan.Page} 才能保证每个适配器
     *              的分页语义一致，而不是各自往 SQL 尾巴上加
     * @param limit 单页条数上限
     */
    public Plan plan(ObjectType type, Rel rel, Cursor after, int limit) {
        if (!caps.reverseIndex()) {
            throw new EvalException(
                    "存储未声明反向索引能力，拒绝反查。不做全表扫后过滤的静默降级——"
                            + "那种降级会在数据量长上来之后变成线上事故。");
        }
        var def = schema.relation(type, rel);
        if (!def.listable()) {
            throw new SchemaException(
                    type.name() + '#' + rel.name() + " 未声明 listable，不能反查。"
                            + "能不能反查是架构事实，不该由调用方事后决定。");
        }
        var path = new LinkedHashSet<Node>();
        path.add(new Node(type, rel));
        return new Plan.Page(compile(def.rewrite(), type, path), after, limit);
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，这里立刻编译失败。 */
    private Plan compile(Perm perm, ObjectType type, Set<Node> path) {
        return switch (perm) {
            case Perm.Direct(var rel) -> new Plan.ScanReverse(rel, type);
            case Perm.AnyOf(var terms) -> new Plan.Union(compileAll(terms, type, path));
            case Perm.AllOf(var terms) -> new Plan.Intersect(compileAll(terms, type, path));
            case Perm.Minus(var base, var denied) ->
                    new Plan.Difference(compile(base, type, path), compile(denied, type, path));
            case Perm.Guarded(var base, var cond) -> new Plan.Filter(compile(base, type, path), cond);
            case Perm.Through(var hop, var then) -> expand(hop, then, type, path);
            case Perm.Ref(var rel) -> {
                var node = new Node(type, rel);
                if (!path.add(node)) {
                    throw new SchemaException(
                            "不支持的递归形状：" + type.name() + '#' + rel.name()
                                    + " 的递归引用没有直接出现在 Through 之下，反查无法编成传递闭包");
                }
                var plan = compile(schema.relation(type, rel).rewrite(), type, path);
                path.remove(node);
                yield plan;
            }
        };
    }

    private List<Plan> compileAll(List<Perm> terms, ObjectType type, Set<Node> path) {
        return terms.stream().map(term -> compile(term, type, path)).toList();
    }

    /** hop 声明了多个目标类型时并起来——Validator 已保证声明非空。 */
    private Plan expand(Rel hop, Perm then, ObjectType type, Set<Node> path) {
        var branches = new ArrayList<Plan>();
        for (var target : schema.relation(type, hop).targets()) {
            if (then instanceof Perm.Ref(var rel) && path.contains(new Node(target, rel))) {
                requireRecursiveQuery(target, rel);
                branches.add(new Plan.ExpandUpClosure(baseOf(target, rel, path), hop, type));
            } else {
                branches.add(new Plan.ExpandUp(compile(then, target, path), hop, type));
            }
        }
        return branches.size() == 1 ? branches.getFirst() : new Plan.Union(branches);
    }

    /**
     * 递归定义的基础项：把指回自己的那些 {@code Through(hop, Ref(rel))} 去掉后剩下的部分。
     *
     * <p>这就是递归 CTE 的种子集。要求定义顶层是 {@code AnyOf}，因为只有并集才能把
     * 基础项与递归项分开——递归藏在 {@code AllOf} 或 {@code Minus} 之下时没有这种分解。
     */
    private Plan baseOf(ObjectType type, Rel rel, Set<Node> path) {
        var where = type.name() + '#' + rel.name();
        if (!(schema.relation(type, rel).rewrite() instanceof Perm.AnyOf(var terms))) {
            throw new SchemaException(
                    "不支持的递归形状：" + where + " 的定义不是 AnyOf，反查无法分离基础项与递归项");
        }
        var base = terms.stream().filter(term -> !isSelfRecursive(term, type, rel)).toList();
        if (base.size() == terms.size()) {
            throw new SchemaException(
                    "不支持的递归形状：" + where + " 的递归不是同类型单跳自递归");
        }
        if (base.isEmpty()) {
            throw new SchemaException("递归定义 " + where + " 没有基础项，反查没有种子集");
        }
        return base.size() == 1
                ? compile(base.getFirst(), type, path)
                : new Plan.Union(compileAll(base, type, path));
    }

    /** {@code Through(hop, Ref(rel))} 且 {@code hop} 指回同一个类型。 */
    private boolean isSelfRecursive(Perm term, ObjectType type, Rel rel) {
        return term instanceof Perm.Through(var hop, var then)
                && then instanceof Perm.Ref(var referenced)
                && referenced.equals(rel)
                && schema.relation(type, hop).targets().contains(type);
    }

    private void requireRecursiveQuery(ObjectType type, Rel rel) {
        if (!caps.recursiveQuery()) {
            throw new EvalException(
                    "存储未声明递归查询能力，拒绝反查递归定义 " + type.name() + '#' + rel.name()
                            + "。不在应用层循环展开——那是把 N+1 查询藏进内核。");
        }
    }
}

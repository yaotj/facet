package facet.core.eval;

import facet.core.ir.ObjectRef;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.TupleSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.stream.Collectors;

/**
 * 第三条求值路径：<strong>展开</strong>——"谁能对这个对象做这件事"。
 *
 * <p>{@code check} 回答单点、{@code lookupResources} 回答"我能碰哪些资源"，这两条都不能
 * 回答"这份文档共享给了谁"。而后者是审计、权限管理界面、离职交接这些场景的基本操作。
 *
 * <p>它<strong>不需要反向索引</strong>：沿着 {@code Perm} 正向走，用的是
 * {@code subjects()} 与 {@code targets()}，与 check 同一组端口能力。因此每个适配器
 * 天然支持，不需要新的 {@code Caps} 标记。
 *
 * <p>三处语义边界值得写清：
 * <ul>
 *   <li><strong>结果展开到具体主体。</strong>userset 会被递归展开成 {@code Principal}，
 *       因为 {@code Minus} 要在同一粒度上做差——"组成员减去被禁用的人"必须落到人。</li>
 *   <li><strong>条件按给定上下文求值。</strong>{@code Guarded} 里的 CONTEXT 属性来自
 *       {@code Ctx.Request}，所以展开的语义是"在这样的上下文下，谁能做"。审计要问
 *       "如果没过 MFA 呢"，把上下文换掉再问一次即可。</li>
 *   <li><strong>主体在请求上下文里无意义。</strong>展开不针对某个主体，{@code Ctx} 里的
 *       principal 只是占位。这一点靠文档约束，因为 {@code Ctx} 的形状是为 check 定的。</li>
 * </ul>
 */
public final class Expander {

    private final Schema schema;
    private final TupleSource tuples;
    private final AttrSource attrs;

    public Expander(Schema schema, TupleSource tuples, AttrSource attrs) {
        this.schema = schema;
        this.tuples = tuples;
        this.attrs = attrs;
    }

    /**
     * 能对 {@code obj} 做 {@code rel} 的全部具体主体。
     *
     * @return 按 {@link SubjectRef#ORDER} 排序，保证跨适配器结果可比对
     */
    public SequencedSet<SubjectRef.Principal> subjects(ObjectRef obj, Rel rel) {
        var request = Ctx.current();
        var found = expand(schema.relation(obj.type(), rel).rewrite(), obj,
                Trail.root(request.maxNodes(), request.deadline()));
        return found.stream()
                .sorted(SubjectRef.ORDER)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，展开这里立刻编译失败。 */
    private SequencedSet<SubjectRef.Principal> expand(Perm perm, ObjectRef obj, Trail trail) {
        // 展开没有记忆化，同一个对象可能被多条路径重复展开，工作预算在这条路上比 check 更要紧
        trail.charge();
        var key = new Memo.Key(perm, obj);
        if (trail.contains(key) || trail.depth() >= Ctx.current().maxDepth()) {
            // 环与超深都返回空集：展开是"收集"，缺一部分比无限递归好，
            // 而环本身在 check 路径上已经会被报成 CYCLE-CUT
            return new LinkedHashSet<>();
        }
        var path = trail.push(key);

        return switch (perm) {
            case Perm.Direct(var rel) -> direct(rel, obj, path);
            case Perm.Ref(var rel) -> expand(schema.relation(obj.type(), rel).rewrite(), obj, path);
            case Perm.AnyOf(var terms) -> union(terms, obj, path);
            case Perm.AllOf(var terms) -> intersect(terms, obj, path);
            case Perm.Minus(var base, var denied) -> {
                var out = expand(base, obj, path);
                out.removeAll(expand(denied, obj, path));
                yield out;
            }
            case Perm.Through(var hop, var then) -> {
                var out = new LinkedHashSet<SubjectRef.Principal>();
                var targets = tuples.targets(obj, hop);
                guardFanout(targets.size(), "Through(" + hop.name() + ")");
                targets.forEach(target -> out.addAll(expand(then, target, path)));
                yield out;
            }
            case Perm.Guarded(var base, var cond) -> Conds.eval(cond, obj, attrs,
                    Ctx.current().contextAttrs()) ? expand(base, obj, path) : new LinkedHashSet<>();
        };
    }

    /** userset 递归展开成具体主体：{@code Minus} 要在同一粒度上做差。 */
    private SequencedSet<SubjectRef.Principal> direct(Rel rel, ObjectRef obj, Trail trail) {
        var out = new LinkedHashSet<SubjectRef.Principal>();
        var subjects = tuples.subjects(obj, rel);
        guardFanout(subjects.size(), "Direct(" + rel.name() + ")");
        for (var subject : subjects) {
            switch (subject) {
                case SubjectRef.Principal principal -> out.add(principal);
                case SubjectRef.Userset(var object, var relation) ->
                        out.addAll(expand(new Perm.Direct(relation), object, trail));
            }
        }
        return out;
    }

    private SequencedSet<SubjectRef.Principal> union(List<Perm> terms, ObjectRef obj, Trail trail) {
        var out = new LinkedHashSet<SubjectRef.Principal>();
        terms.forEach(term -> out.addAll(expand(term, obj, trail)));
        return out;
    }

    private SequencedSet<SubjectRef.Principal> intersect(List<Perm> terms, ObjectRef obj, Trail trail) {
        var out = expand(terms.getFirst(), obj, trail);
        terms.subList(1, terms.size()).forEach(term -> out.retainAll(expand(term, obj, trail)));
        return out;
    }

    /** 与 check 用同一个上限：热点对象上展开的成本和扇出一样会失控。 */
    private void guardFanout(int width, String where) {
        int limit = tuples.caps().maxFanout();
        if (width > limit) {
            throw new EvalException(where + " 展开宽度 " + width + " 超过存储声明的上限 " + limit);
        }
    }
}

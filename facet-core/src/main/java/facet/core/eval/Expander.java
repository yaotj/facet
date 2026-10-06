package facet.core.eval;

import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.TupleSource;
import facet.core.sem.Conds;
import facet.core.sem.Keys;
import facet.core.runtime.Ctx;
import facet.core.runtime.Memo;
import facet.core.schema.Schema;
import facet.core.runtime.EvalException;

import java.util.Comparator;
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
 * <p>四处语义边界值得写清：
 * <ul>
 *   <li><strong>结果展开到具体主体。</strong>userset 会被递归展开成 {@code Principal}，
 *       因为 {@code Minus} 要在同一粒度上做差——"组成员减去被禁用的人"必须落到人。</li>
 *   <li><strong>通配主体展不开，单独报出来。</strong>{@code user:*} 是一个开放集合，
 *       落不成具体主体。静默忽略它会让"谁能看这份文档"漏掉"所有人"——对审计来说，
 *       低估访问面是最危险的方向。所以它出现在 {@link Subjects#anyOf()} 里。</li>
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
     * 展开结果。
     *
     * <p>两部分不能合成一个集合：{@code principals} 是有限枚举，{@code anyOf} 是开放集合。
     * 把后者硬塞进前者就得把整张用户表读出来，而那恰恰是通配主体要避免的事。
     *
     * @param principals 具体主体，按排序键升序的一页
     * @param anyOf      被授予了通配的主体类型。它<strong>不参与分页</strong>——
     *                   至多是 schema 里主体类型的个数，每页都完整给出
     */
    public record Subjects(SequencedSet<SubjectRef.Principal> principals,
                           SequencedSet<ObjectType> anyOf) {}

    /**
     * 能对 {@code obj} 做 {@code rel} 的主体，取一页。
     *
     * <p><strong>必须给上限，和反查一样。</strong>结果集大小由数据决定而不由请求决定：一份挂在
     * 大目录下的文档，"谁能看"可能是三个人也可能是三万人。没有上限，一次审计查询就能拉出一个
     * 几十兆的响应。
     *
     * <p><strong>但这个上限只约束响应，不约束工作量。</strong>这一点和反查不同，必须讲清楚：
     * 反查能把 {@code LIMIT} 下推进 SQL，展开是沿 {@code Perm} 正向走的，必须先把集合算完
     * 才能排序取页。约束工作量的是另外三样——每步扇出上限、{@code maxNodes} 工作预算、
     * 以及可选的墙钟期限。想少做工作就得收窄 schema 或改用反查，而不是把 limit 调小。
     *
     * @param after 上一页最后一个主体的游标；首页传 {@link Cursor#START}
     * @param limit 单页具体主体数上限
     */
    public Subjects subjects(ObjectRef obj, Rel rel, Cursor after, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("单页上限必须为正");
        }
        var request = Ctx.current();
        var found = expand(schema.relation(obj.type(), rel).rewrite(), obj,
                Trail.root(request.maxNodes(), request.deadline()));
        // 排序与游标比较都走 Cursor.keyOf + Keys：两者必须是同一个序，否则分页会漏项或重项。
        // 用字节序而不是 SubjectRef.ORDER 的 UTF-16 序，是为了和 PG 的 COLLATE "C" 对齐。
        var page = found.principals.stream()
                .sorted(Comparator.comparing(Cursor::keyOf, Keys.ORDER))
                .filter(subject -> Keys.compare(Cursor.keyOf(subject), after.token()) > 0)
                .limit(limit)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var anyOf = found.anyOf.stream()
                .sorted(Comparator.comparing(ObjectType::name, Keys.ORDER))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Subjects(page, anyOf);
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，展开这里立刻编译失败。 */
    private Found expand(Perm perm, ObjectRef obj, Trail trail) {
        // 展开没有记忆化，同一个对象可能被多条路径重复展开，工作预算在这条路上比 check 更要紧
        trail.charge();
        var key = new Memo.Key(perm, obj);
        if (trail.contains(key) || trail.depth() >= Ctx.current().maxDepth()) {
            // 环与超深都返回空集：展开是"收集"，缺一部分比无限递归好，
            // 而环本身在 check 路径上已经会被报成 CYCLE-CUT
            return new Found();
        }
        var path = trail.push(key);

        return switch (perm) {
            case Perm.Direct(var rel) -> direct(rel, obj, path);
            case Perm.Ref(var rel) -> expand(schema.relation(obj.type(), rel).rewrite(), obj, path);
            case Perm.AnyOf(var terms) -> union(terms, obj, path);
            case Perm.AllOf(var terms) -> intersect(terms, obj, path);
            case Perm.Minus(var base, var denied) ->
                    difference(expand(base, obj, path), expand(denied, obj, path));
            case Perm.Through(var hop, var then) -> {
                var out = new Found();
                var targets = tuples.targets(obj, hop);
                guardFanout(targets.size(), "Through(" + hop.name() + ")");
                targets.forEach(target -> out.addAll(expand(then, target, path)));
                yield out;
            }
            case Perm.Guarded(var base, var cond) -> Conds.eval(cond, obj, attrs,
                    Ctx.current().contextAttrs()) ? expand(base, obj, path) : new Found();
        };
    }

    /** userset 递归展开成具体主体；通配主体收进 {@code anyOf}，它展不开。 */
    private Found direct(Rel rel, ObjectRef obj, Trail trail) {
        var out = new Found();
        var subjects = tuples.subjects(obj, rel);
        guardFanout(subjects.size(), "Direct(" + rel.name() + ")");
        for (var subject : subjects) {
            switch (subject) {
                case SubjectRef.Principal principal -> out.principals.add(principal);
                case SubjectRef.Wildcard(var type) -> out.anyOf.add(type);
                case SubjectRef.Userset(var object, var relation) ->
                        out.addAll(expand(new Perm.Direct(relation), object, trail));
            }
        }
        return out;
    }

    private Found union(List<Perm> terms, ObjectRef obj, Trail trail) {
        var out = new Found();
        terms.forEach(term -> out.addAll(expand(term, obj, trail)));
        return out;
    }

    /**
     * 交集。
     *
     * <p>不能逐字段取交：若一支给出 {@code anyOf={user}}、另一支给出
     * {@code principals={user:alice}}，alice 属于交集——她是 user，被通配那一支覆盖。
     * 漏掉这种组合会让 {@code AllOf} 在有通配时少算主体。
     */
    private Found intersect(List<Perm> terms, ObjectRef obj, Trail trail) {
        var acc = expand(terms.getFirst(), obj, trail);
        for (var term : terms.subList(1, terms.size())) {
            var next = expand(term, obj, trail);
            var kept = new Found();
            var left = acc;
            left.principals.stream()
                    .filter(p -> next.principals.contains(p) || next.anyOf.contains(p.type()))
                    .forEach(kept.principals::add);
            next.principals.stream()
                    .filter(p -> left.anyOf.contains(p.type()))
                    .forEach(kept.principals::add);
            next.anyOf.stream().filter(left.anyOf::contains).forEach(kept.anyOf::add);
            acc = kept;
        }
        return acc;
    }

    /**
     * 差集。
     *
     * <p>唯一一处会拒绝的地方：base 留下的通配类型上还有具体的被拒主体时，真实答案是
     * "这个类型的所有主体，除了某几个"。忠实表达它需要在结果里再带一个排除集，
     * 而那会渗进 wire 格式和每个消费方。
     *
     * <p>拒绝只发生在<strong>展开</strong>路径，而且只在通配真的出现时——check 与反查都是
     * 针对具体主体求值，{@code Minus} 在那两条路上照常工作。这也意味着它不能做成加载期规则：
     * 通配是否流到 {@code Minus} 的 base 由数据决定，不由 schema 决定。
     */
    private static Found difference(Found base, Found denied) {
        var remaining = new LinkedHashSet<>(base.anyOf);
        remaining.removeAll(denied.anyOf);
        if (!remaining.isEmpty() && !denied.principals.isEmpty()) {
            throw new EvalException("展开无法表达\"某类型的全部主体，除了其中几个\"："
                    + remaining + " 上有通配授权，同时存在具体的被拒主体 " + denied.principals
                    + "；请改用 check 逐个判定，或调整 schema 让否定作用在通配之外");
        }
        var out = new Found();
        out.anyOf.addAll(remaining);
        base.principals.stream()
                .filter(p -> !denied.principals.contains(p) && !denied.anyOf.contains(p.type()))
                .forEach(out.principals::add);
        return out;
    }

    /** 与 check 用同一个上限：热点对象上展开的成本和扇出一样会失控。 */
    private void guardFanout(int width, String where) {
        int limit = tuples.caps().maxFanout();
        if (width > limit) {
            throw new EvalException(where + " 展开宽度 " + width + " 超过存储声明的上限 " + limit);
        }
    }

    /** 递归过程中的可变累加器。对外暴露的是不可变的 {@link Subjects}。 */
    private static final class Found {

        private final LinkedHashSet<SubjectRef.Principal> principals = new LinkedHashSet<>();
        private final LinkedHashSet<ObjectType> anyOf = new LinkedHashSet<>();

        void addAll(Found other) {
            principals.addAll(other.principals);
            anyOf.addAll(other.anyOf);
        }
    }
}

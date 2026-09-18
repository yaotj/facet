package facet.store.memory;

import facet.core.eval.Conds;
import facet.core.eval.Ctx;
import facet.core.eval.Keys;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Plan;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.PlanExecutor;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link Plan} 的参考执行器。
 *
 * <p>内存实现里所有算子都退化成 {@code LinkedHashSet} 运算——真实适配器应当把 Union /
 * Intersect / Difference / Page 下推成一条 SQL，而不是把这份代码抽成共享基类。它在这里
 * 的作用是给出算子语义的判定基准。
 */
public final class MemoryPlanExecutor implements PlanExecutor {

    private final MemoryTupleSource tuples;
    private final AttrSource attrs;

    /**
     * @param tuples 声明成 {@link MemoryTupleSource} 而不是端口类型：反查要用到端口上没有的
     *               {@link MemoryTupleSource#subjectClosure}
     */
    public MemoryPlanExecutor(MemoryTupleSource tuples, AttrSource attrs) {
        this.tuples = tuples;
        this.attrs = attrs;
    }

    /** 整棵计划先求值成有序集合再转 Stream：算子定义在集合上，逐元素惰性化没有意义。 */
    @Override
    public Stream<ObjectRef> execute(Plan plan) {
        return eval(plan).stream();
    }

    /** 没有 {@code default} 分支：{@code Plan} 加算子，每个适配器都会立刻编译失败。 */
    private SequencedSet<ObjectRef> eval(Plan plan) {
        return switch (plan) {
            case Plan.ScanReverse(var rel, var type) -> scan(rel, type);
            case Plan.Union(var inputs) -> {
                var out = new LinkedHashSet<ObjectRef>();
                inputs.forEach(input -> out.addAll(eval(input)));
                yield out;
            }
            case Plan.Intersect(var inputs) -> {
                var out = new LinkedHashSet<>(eval(inputs.getFirst()));
                inputs.subList(1, inputs.size()).forEach(input -> out.retainAll(eval(input)));
                yield out;
            }
            case Plan.Difference(var left, var right) -> {
                var out = new LinkedHashSet<>(eval(left));
                out.removeAll(eval(right));
                yield out;
            }
            case Plan.ExpandUp(var inner, var hop, var outer) -> expandUp(inner, hop, outer);
            case Plan.ExpandUpClosure(var inner, var hop, var outer) -> closure(inner, hop, outer);
            case Plan.Filter(var input, var cond) -> {
                var request = Ctx.current();
                var out = new LinkedHashSet<ObjectRef>();
                eval(input).stream()
                        .filter(obj -> Conds.eval(cond, obj, attrs, request.contextAttrs()))
                        .forEach(out::add);
                yield out;
            }
            case Plan.Page(var input, var after, var limit) -> page(eval(input), after, limit);
        };
    }

    /** 反向索引扫描要覆盖主体的整个 userset 闭包，否则间接授权在列表里会丢。 */
    private SequencedSet<ObjectRef> scan(Rel rel, ObjectType type) {
        var out = new LinkedHashSet<ObjectRef>();
        tuples.subjectClosure(Ctx.current().principal())
                .forEach(subject -> tuples.objects(subject, rel, type).forEach(out::add));
        return out;
    }

    /**
     * 方向翻转的落点：{@code inner} 产出的是 hop 的<strong>目标</strong>（folder），
     * 这里要反过来找出所有指向它们的 {@code outer} 对象（doc）。
     */
    private SequencedSet<ObjectRef> expandUp(Plan inner, Rel hop, ObjectType outer) {
        var out = new LinkedHashSet<ObjectRef>();
        for (var target : eval(inner)) {
            var asSubject = new SubjectRef.Principal(target.type(), target.id());
            tuples.objects(asSubject, hop, outer).forEach(out::add);
        }
        return out;
    }

    /**
     * {@code Perm.Ref} 自递归的落点：沿 hop 反向逐层展开到不动点，不含种子集自身。
     *
     * <p>真实适配器这里应当发一条 {@code WITH RECURSIVE}；这份实现用 BFS 给出语义基准，
     * 并且带 seen 集合防数据成环（{@code folder:a#parent@folder:b} 与反向同时存在）。
     */
    private SequencedSet<ObjectRef> closure(Plan inner, Rel hop, ObjectType outer) {
        var seeds = eval(inner);
        var out = new LinkedHashSet<ObjectRef>();
        var seen = new LinkedHashSet<>(seeds);
        var frontier = new ArrayDeque<>(seeds);
        while (!frontier.isEmpty()) {
            var current = frontier.poll();
            var asSubject = new SubjectRef.Principal(current.type(), current.id());
            tuples.objects(asSubject, hop, outer).forEach(found -> {
                out.add(found);
                if (seen.add(found)) {
                    frontier.add(found);
                }
            });
        }
        return out;
    }

    /** 键游标分页：按排序键取游标之后的一页，不用 offset。 */
    private SequencedSet<ObjectRef> page(SequencedSet<ObjectRef> input, Cursor after, int limit) {
        // 排序与比较都走 Keys（UTF-8 字节序）：String.compareTo 是 UTF-16 码元序，
        // 与 PG 的 COLLATE "C" 在补充平面字符上顺序相反，同一游标会翻到不同页。
        return input.stream()
                .sorted(Comparator.comparing(Cursor::keyOf, Keys.ORDER))
                .filter(obj -> Keys.compare(Cursor.keyOf(obj), after.token()) > 0)
                .limit(limit)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

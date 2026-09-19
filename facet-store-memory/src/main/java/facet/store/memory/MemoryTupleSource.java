package facet.store.memory;

import facet.core.eval.Ctx;
import facet.core.eval.Keys;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleFilter;
import facet.core.spi.TupleSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 内存元组存储。
 *
 * <p>不只是测试替身，而是<strong>端口语义的基准</strong>：反向索引、扇出上限都支持，
 * 因此任何"在这里通过、换到真实存储后失败"的行为都能立刻定位成适配器缺陷而非内核缺陷。
 *
 * <p>只有一个版本，所以 {@code snapshotRead=false}，并且显式拒绝非 HEAD 的读——
 * 静默忽略一致性坐标会让"写后一致读"这个保证在换存储之前一直看起来是成立的。
 *
 * <p>索引用并发容器：它会被装进 PDP，而 PDP 每请求一个虚拟线程、扇出还可能并行，
 * 普通 {@code HashMap} 在读写并发下会抛 {@code ConcurrentModificationException}
 * 甚至结构损坏。写入不保证与读取的原子性（那需要版本化，见 PG 适配器），
 * 但至少不会把数据结构弄坏。
 */
public final class MemoryTupleSource implements TupleSource {

    private record Fwd(ObjectRef object, Rel relation) {}

    private record Rev(SubjectRef subject, Rel relation) {}

    private final Map<Fwd, Set<SubjectRef>> forward = new ConcurrentHashMap<>();
    private final Map<Rev, Set<ObjectRef>> reverse = new ConcurrentHashMap<>();
    private final Map<SubjectRef, Set<SubjectRef.Userset>> memberships = new ConcurrentHashMap<>();
    private final int maxFanout;

    /**
     * @param maxFanout 对外公布的扇出上限；内存实现不下推 {@code LIMIT}，实际拦截由
     *                  {@code Checker} 依这个声明完成
     */
    public MemoryTupleSource(int maxFanout) {
        this.maxFanout = maxFanout;
    }

    /** 扇出上限取 1024：内存实现不受连接池约束，这个默认值只是为了与 PG 适配器保持一致。 */
    public MemoryTupleSource() {
        this(1024);
    }

    /** 变参形式，便于测试里直接写出字面元组；语义与 {@link #write(Collection)} 完全一致。 */
    public MemoryTupleSource write(Tuple... tuples) {
        return write(List.of(tuples));
    }

    /**
     * 追加元组，并同批维护正向、反向与 membership 三份索引。
     *
     * <p>三份索引必须一起更新：check 走正向，反查走反向，userset 闭包走 membership。
     * 漏掉任何一份都会让同一份数据在 check 与反查上给出矛盾答案。
     *
     * <p>没有版本概念，所以写入是纯追加且重复写入无副作用；需要撤销请换 PG 适配器。
     *
     * @return this，便于连写多次 write
     */
    public MemoryTupleSource write(Collection<Tuple> tuples) {
        for (var tuple : tuples) {
            forward.computeIfAbsent(new Fwd(tuple.object(), tuple.relation()), _ -> concurrentSet())
                    .add(tuple.subject());
            reverse.computeIfAbsent(new Rev(tuple.subject(), tuple.relation()), _ -> concurrentSet())
                    .add(tuple.object());
            memberships.computeIfAbsent(tuple.subject(), _ -> concurrentSet())
                    .add(new SubjectRef.Userset(tuple.object(), tuple.relation()));
        }
        return this;
    }

    @Override
    public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
        requireHead();
        // 端口要求确定顺序：explain 的分支顺序要和 PG 适配器一致，否则判定矩阵对不上。
        return forward.getOrDefault(new Fwd(obj, rel), Set.of()).stream()
                .sorted(SubjectRef.ORDER)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
        requireHead();
        return reverse.getOrDefault(new Rev(subject, rel), Set.of()).stream()
                .filter(obj -> obj.type().equals(type))
                .sorted(java.util.Comparator.comparing(ObjectRef::id, facet.core.eval.Keys.ORDER));
    }

    /**
     * 按条件读回元组。
     *
     * <p>排序与 PG 适配器对齐：六列升序、字节序（{@code Keys.ORDER} 对应 PG 的
     * {@code COLLATE "C"}）。顺序必须一致，否则同一个游标在两个存储上会翻到不同页。
     *
     * @param after 上一页最后一条元组；首页传 {@code null}
     */
    public List<Tuple> read(TupleFilter filter, Tuple after, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("单页上限必须为正");
        }
        requireHead();
        var all = new ArrayList<Tuple>();
        forward.forEach((key, subjects) -> subjects.forEach(subject ->
                all.add(new Tuple(key.object(), key.relation(), subject))));
        return all.stream()
                .filter(filter::matches)
                .sorted(BY_KEY)
                .filter(tuple -> after == null || BY_KEY.compare(tuple, after) > 0)
                .limit(limit)
                .toList();
    }

    /** 六列字典序，逐列走字节序比较——与 PG 的 {@code ORDER BY ... COLLATE "C"} 是同一个序。 */
    private static final Comparator<Tuple> BY_KEY = Comparator
            .comparing((Tuple t) -> t.object().type().name(), Keys.ORDER)
            .thenComparing(t -> t.object().id(), Keys.ORDER)
            .thenComparing(t -> t.relation().name(), Keys.ORDER)
            .thenComparing(t -> subjectKey(t.subject()).get(0), Keys.ORDER)
            .thenComparing(t -> subjectKey(t.subject()).get(1), Keys.ORDER)
            .thenComparing(t -> subjectKey(t.subject()).get(2), Keys.ORDER);

    /**
     * 主体的三列表示。
     *
     * <p>空 rel 表示具体主体、空 id 表示通配主体——与 PG 表里的两个哨兵值一致，
     * 排序因此跨适配器相同。
     */
    private static List<String> subjectKey(SubjectRef subject) {
        return switch (subject) {
            case SubjectRef.Principal(var type, var id) -> List.of(type.name(), id, "");
            case SubjectRef.Userset(var object, var relation) ->
                    List.of(object.type().name(), object.id(), relation.name());
            case SubjectRef.Wildcard(var type) -> List.of(type.name(), "", "");
        };
    }

    /**
     * 主体的 userset 闭包：主体本身，加上它（递归）所属的每个 {@code object#relation}。
     *
     * <p>反查必须先算这个闭包，否则 {@code doc:readme#viewer@group:eng#member} 这类间接
     * 授权在列表接口里会全部丢失——而 check 路径却能命中，两条路给出矛盾答案。
     *
     * <p>返回值保留插入顺序（{@code Set.copyOf} 会把它打散成 JVM 随机序），
     * 因为 {@code ScanReverse} 依此顺序累积结果。
     *
     * <p>闭包里还播下一颗通配种子（{@code user:*}）：通配授权在反查里只是闭包多出来的一行，
     * 与 PG 侧那条 {@code SELECT ?::text, '', ''} 等价。反查问的是"这个<em>具体</em>主体能碰
     * 哪些"，所以通配在这条路上不构成开放集合。
     */
    public Set<SubjectRef> subjectClosure(SubjectRef principal) {
        var seen = new LinkedHashSet<SubjectRef>();
        var frontier = new ArrayDeque<SubjectRef>();
        frontier.add(principal);
        frontier.add(new SubjectRef.Wildcard(new ObjectType(subjectKey(principal).getFirst())));
        while (!frontier.isEmpty()) {
            var current = frontier.poll();
            if (seen.add(current)) {
                memberships.getOrDefault(current, Set.of()).stream()
                        .sorted(SubjectRef.ORDER)
                        .forEach(frontier::add);
            }
        }
        return Collections.unmodifiableSet(seen);
    }

    /** 反向索引与递归展开由内存结构直接支持；{@code snapshotRead=false}，因为这里只存一个版本。 */
    @Override
    public Caps caps() {
        return new Caps(true, false, true, maxFanout);
    }

    private void requireHead() {
        var at = Ctx.current().at();
        if (!at.isHead()) {
            throw new UnsupportedOperationException(
                    "内存存储只有一个版本，无法在 Revision " + at.value() + " 上读；"
                            + "需要快照读请换声明了 snapshotRead 的适配器");
        }
    }

    private static <T> Set<T> concurrentSet() {
        return ConcurrentHashMap.newKeySet();
    }
}

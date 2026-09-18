package facet.store.memory;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.spi.TupleSource;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
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

    public MemoryTupleSource(int maxFanout) {
        this.maxFanout = maxFanout;
    }

    public MemoryTupleSource() {
        this(1024);
    }

    public MemoryTupleSource write(Tuple... tuples) {
        return write(List.of(tuples));
    }

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
     * 主体的 userset 闭包：主体本身，加上它（递归）所属的每个 {@code object#relation}。
     *
     * <p>反查必须先算这个闭包，否则 {@code doc:readme#viewer@group:eng#member} 这类间接
     * 授权在列表接口里会全部丢失——而 check 路径却能命中，两条路给出矛盾答案。
     *
     * <p>返回值保留插入顺序（{@code Set.copyOf} 会把它打散成 JVM 随机序），
     * 因为 {@code ScanReverse} 依此顺序累积结果。
     */
    public Set<SubjectRef> subjectClosure(SubjectRef principal) {
        var seen = new LinkedHashSet<SubjectRef>();
        var frontier = new ArrayDeque<SubjectRef>();
        frontier.add(principal);
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

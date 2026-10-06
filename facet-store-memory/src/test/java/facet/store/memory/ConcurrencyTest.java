package facet.store.memory;

import facet.core.eval.Attrs;
import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.spi.decorators.PrefetchedAttrs;
import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.ir.Tuple;
import facet.testkit.RandomScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发安全的实测。
 *
 * <p>索引用并发容器、计数器用原子类、{@code PrefetchedAttrs} 用不可变 map——这些都是
 * 声明，而声明需要被验证。这个库的运行形态是"每 check 一个虚拟线程 + 扇出可能并行"，
 * 没有并发测试就等于这一整类问题从未被检验过。
 */
class ConcurrencyTest {

    private static final int THREADS = 64;
    private static final int ROUNDS = 50;

    /** 读写并发下数据结构不能损坏。HashMap 在这个用例上会抛 CME 或产出错乱结果。 */
    @Test
    void readsAndWritesDoNotCorruptTheStore() throws Exception {
        var scenario = RandomScenario.of(7);
        var tuples = new MemoryTupleSource().write(scenario.tuples());
        var attrs = new MemoryAttrSource();
        var checker = new Checker(scenario.schema(), tuples, attrs);
        var subject = scenario.subjects().getFirst();

        var tasks = new ArrayList<Callable<Integer>>();
        for (int t = 0; t < THREADS; t++) {
            int worker = t;
            tasks.add(() -> {
                int allowed = 0;
                for (int round = 0; round < ROUNDS; round++) {
                    // 一边读一边写：写入的是与判定无关的噪声元组，读的结果不需要确定，
                    // 但过程不能抛异常，也不能把索引结构弄坏
                    tuples.write(Tuple.of(
                            new ObjectRef(RandomScenario.DOC, "w" + worker + '-' + round),
                            RandomScenario.VIEWER,
                            new ObjectRef(RandomScenario.USER, "w" + worker)));
                    var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
                    for (var doc : scenario.docs()) {
                        if (Ctx.run(request, () -> checker.check(doc, RandomScenario.VIEW)).allowed()) {
                            allowed++;
                        }
                    }
                }
                return allowed;
            });
        }

        var results = runAll(tasks);
        // 每个 worker 看到的授权数应当一致：写入的噪声与这个主体的判定无关
        assertEquals(1, results.stream().distinct().count(),
                "并发读写期间判定结果发生了漂移: " + results.stream().distinct().toList());
    }

    /** 计数器不能丢：它存在的理由就是让"批量化生效了没有"可被断言。 */
    @Test
    void externalReadCountingIsAtomic() throws Exception {
        var attrs = new MemoryAttrSource();
        var key = AttrKey.bool("employed", AttrKey.Tier.EXTERNAL);
        var doc = new ObjectRef(RandomScenario.DOC, "d0");
        attrs.put(doc, key, true);

        var tasks = new ArrayList<Callable<Object>>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                for (int round = 0; round < ROUNDS; round++) {
                    attrs.value(key, doc);
                }
                return null;
            });
        }
        runAll(tasks);

        assertEquals(THREADS * ROUNDS, attrs.externalReads());
    }

    /** 预取结果是不可变快照，并发读必须稳定，而且不能回源。 */
    @Test
    void prefetchedAttributesAreStableUnderConcurrentReads() throws Exception {
        var key = AttrKey.number("level", AttrKey.Tier.SNAPSHOT);
        var docs = List.of(new ObjectRef(RandomScenario.DOC, "d0"),
                new ObjectRef(RandomScenario.DOC, "d1"));
        var backing = new MemoryAttrSource().put(docs.getFirst(), key, "5");
        var counting = new AtomicInteger();
        var prefetched = PrefetchedAttrs.of(new CountingAttrs(backing, counting),
                java.util.Set.of(key), docs);
        int afterPrefetch = counting.get();

        var tasks = new ArrayList<Callable<String>>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                var seen = new StringBuilder();
                for (int round = 0; round < ROUNDS; round++) {
                    seen.setLength(0);
                    docs.forEach(doc -> seen.append(prefetched.value(key, doc)).append('|'));
                }
                return seen.toString();
            });
        }

        var results = runAll(tasks);
        assertEquals(1L, results.stream().distinct().count(), results.stream().distinct().toList()::toString);
        assertEquals("5|null|", results.getFirst());
        assertEquals(afterPrefetch, counting.get(), "命中预取的属性不该回源");
    }

    /** 静态收集本身是纯函数，但要确认它在并发下没有共享可变状态。 */
    @Test
    void attributeCollectionIsReentrant() throws Exception {
        var scenario = RandomScenario.of(3);
        var expected = Attrs.localKeys(scenario.schema(), RandomScenario.DOC, RandomScenario.VIEW);

        var tasks = new ArrayList<Callable<Object>>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> Attrs.localKeys(scenario.schema(), RandomScenario.DOC, RandomScenario.VIEW));
        }

        runAll(tasks).forEach(actual -> assertEquals(expected, actual));
    }

    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(tasks);
            var out = new ArrayList<T>(futures.size());
            for (var future : futures) {
                out.add(future.get());
            }
            return out;
        }
    }

    /** 计量回源次数，用来断言预取真的挡住了回源。 */
    private record CountingAttrs(MemoryAttrSource delegate, AtomicInteger reads)
            implements facet.core.spi.AttrSource {

        @Override
        public Object value(AttrKey key, ObjectRef obj) {
            reads.incrementAndGet();
            return delegate.value(key, obj);
        }

        @Override
        public java.util.Map<ObjectRef, Object> values(AttrKey key,
                                                       java.util.Collection<ObjectRef> objects) {
            reads.incrementAndGet();
            return delegate.values(key, objects);
        }
    }
}

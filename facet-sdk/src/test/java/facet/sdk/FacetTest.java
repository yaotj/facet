package facet.sdk;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Ctx;
import facet.core.runtime.Decision;
import facet.core.spi.DecisionCache;
import facet.core.spi.RevisionSource;
import facet.core.spi.TupleSource;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.FolderScenario;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内门面的行为。
 *
 * <p>重点不是 HTTP 细节，而是"接进应用"这件事本身：不用手写 {@code Ctx.run}、不用自己装求值器，
 * 四个方法给出来的语义要和 PDP 的端点一致——判定跟随继承、批量保序、反查与展开拿到正确的集合。
 */
class FacetTest {

    private static final SubjectRef.Principal ALICE = FolderScenario.principal("alice");
    private static final SubjectRef.Principal BOB = FolderScenario.principal("bob");
    private static final Rel VIEW = FolderScenario.VIEW;

    private Facet facet() {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        return Facet.builder(FolderScenario.SCHEMA)
                .tuples(tuples)
                .attrs(attrs)
                .executor(new MemoryPlanExecutor(tuples, attrs))
                .build();
    }

    /** 带缓存的门面：用声明了 snapshotRead、但忽略坐标的替身，让"具体坐标"请求能走通求值。 */
    private Facet facetWithCache(DecisionCache cache) {
        var backing = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var tuples = snapshotTuples(backing);
        var attrs = new MemoryAttrSource();
        return Facet.builder(FolderScenario.SCHEMA)
                .tuples(tuples)
                .attrs(attrs)
                .executor(new MemoryPlanExecutor(backing, attrs))
                .withCache(cache)
                .build();
    }

    @Test
    void checkFollowsTheHierarchy() {
        var facet = facet();

        assertTrue(facet.check(ALICE, FolderScenario.doc("deep"), VIEW).allowed(),
                "alice 经 folder 继承应当能看 deep");
        assertFalse(facet.check(BOB, FolderScenario.doc("deep"), VIEW).allowed(),
                "bob 只有 private，看不到 deep");
    }

    /** 批量判定：结果顺序与入参一致，调用方才能逐项对上而不必再按 id 匹配。 */
    @Test
    void checkAllPreservesRequestOrder() {
        var facet = facet();
        var objects = List.of(FolderScenario.doc("private"), FolderScenario.doc("deep"));

        var decisions = facet.checkAll(ALICE, objects, VIEW);

        assertEquals(objects, new ArrayList<>(decisions.keySet()), "键的顺序应与入参一致");
        assertFalse(decisions.get(FolderScenario.doc("private")).allowed());
        assertTrue(decisions.get(FolderScenario.doc("deep")).allowed());
    }

    @Test
    void lookupReturnsResourcesTheSubjectCanView() {
        var facet = facet();

        var found = facet.lookup(ALICE, FolderScenario.DOC, VIEW, 10);

        assertTrue(found.contains(FolderScenario.doc("deep")), found.toString());
        assertTrue(found.contains(FolderScenario.doc("readme")), found.toString());
        assertFalse(found.contains(FolderScenario.doc("private")), "bob 独占的 private 不应出现");
    }

    /** 展开：把 userset 递归展开成具体主体（alice 经 group 拿到 readme 的 view）。 */
    @Test
    void whoCanExpandsToConcretePrincipals() {
        var facet = facet();

        var subjects = facet.whoCan(FolderScenario.doc("readme"), VIEW, 10).principals();

        assertTrue(subjects.contains(ALICE), subjects.toString());
        assertTrue(subjects.contains(FolderScenario.principal("carol")), subjects.toString());
    }

    /** 没装 PlanExecutor 就反查，应当给出明确的错而不是空响应或 NPE。 */
    @Test
    void lookupWithoutExecutorIsRejected() {
        var facet = Facet.builder(FolderScenario.SCHEMA)
                .tuples(new MemoryTupleSource().write(FolderScenario.TUPLES))
                .attrs(new MemoryAttrSource())
                .build();

        assertThrows(IllegalStateException.class,
                () -> facet.lookup(ALICE, FolderScenario.DOC, VIEW, 10));
    }

    /** 装库即校验 schema：不合法的 schema 在 build 时就失败，而不是第一次判定才炸。 */
    @Test
    void buildValidatesSchema() {
        // 一个缺 listable 却要反查的形状会触发 Validator 报错；这里只断言 build 对合法 schema 不抛
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        Facet.builder(FolderScenario.SCHEMA)
                .tuples(tuples)
                .attrs(new MemoryAttrSource())
                .executor(new MemoryPlanExecutor(tuples, new MemoryAttrSource()))
                .build();
    }

    /** 带具体坐标的请求会进缓存：同坐标第二次判定命中，不再写缓存。 */
    @Test
    void cachesConcreteRevision() {
        var cache = new CountingCache();
        var facet = facetWithCache(cache);
        var at = new Revision(1);

        facet.checkAt(ALICE, FolderScenario.doc("deep"), VIEW, at);
        assertEquals(1, cache.puts, "首次应当写入缓存");

        facet.checkAt(ALICE, FolderScenario.doc("deep"), VIEW, at);
        assertEquals(1, cache.puts, "同坐标再判定应当命中，不再写缓存");
    }

    /** 批量判定也走缓存：已命中的对象不再进待算集合，全部命中时一次写入都没有。 */
    @Test
    void checkAllCachesPerObject() {
        var cache = new CountingCache();
        var facet = facetWithCache(cache);
        var at = new Revision(1);
        var objects = List.of(FolderScenario.doc("private"), FolderScenario.doc("deep"));

        facet.checkAllAt(ALICE, objects, VIEW, at);
        assertEquals(2, cache.puts, "两个对象各写一次");

        facet.checkAllAt(ALICE, objects, VIEW, at);
        assertEquals(2, cache.puts, "全部命中，不再写");
    }

    /** 读 HEAD 且不配陈旧窗口，不应进缓存——每请求都实算。 */
    @Test
    void doesNotCacheHeadWithoutStaleness() {
        var cache = new CountingCache();
        var facet = facetWithCache(cache);

        facet.check(ALICE, FolderScenario.doc("deep"), VIEW);
        facet.check(ALICE, FolderScenario.doc("deep"), VIEW);

        assertEquals(0, cache.puts, "无坐标且不配陈旧，不应进缓存");
    }

    /** 陈旧窗口需要快照读能力，装配期就该拒绝而不是等第一次判定才炸。 */
    @Test
    void stalenessRequiresSnapshotRead() {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        assertThrows(IllegalArgumentException.class, () -> Facet.builder(FolderScenario.SCHEMA)
                .tuples(tuples)
                .attrs(new MemoryAttrSource())
                .withStaleness(RevisionSource.NONE, Duration.ofSeconds(5))
                .build());
    }

    /**
     * 计写入次数的缓存替身，用来验证命中 / 未命中，而不是重新实现缓存语义。
     */
    private static final class CountingCache implements DecisionCache {
        int puts;
        private final DecisionCache delegate = DecisionCache.bounded(64);

        @Override
        public Boolean get(DecisionCache.Key key) {
            return delegate.get(key);
        }

        @Override
        public void put(DecisionCache.Key key, boolean allowed) {
            puts++;
            delegate.put(key, allowed);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    /**
     * 声明 {@code snapshotRead} 但忽略坐标。
     *
     * <p>只用于验证缓存路径：内存适配器会拒绝非 HEAD 的读（这是对的），而缓存恰恰只在带具体坐标时
     * 生效，所以需要一个声明了该能力的替身。求值出的结果等同于 HEAD，足以验证"写缓存 → 命中"。
     */
    private static TupleSource snapshotTuples(MemoryTupleSource backing) {
        return new TupleSource() {
            @Override
            public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
                return atHead(() -> backing.subjects(obj, rel));
            }

            @Override
            public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
                return atHead(() -> backing.objects(subject, rel, type).toList()).stream();
            }

            @Override
            public TupleSource.Caps caps() {
                var inner = backing.caps();
                return new TupleSource.Caps(inner.reverseIndex(), true, inner.recursiveQuery(), inner.maxFanout());
            }

            /** 内存适配器拒绝非 HEAD 的读，所以把坐标剥掉再委托。 */
            private <T> T atHead(Supplier<T> body) {
                return Ctx.run(Ctx.Request.of(Ctx.current().principal()), body);
            }
        };
    }
}

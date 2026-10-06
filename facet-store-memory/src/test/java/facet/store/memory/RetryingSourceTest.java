package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.AttrSource;
import facet.core.spi.decorators.RetryingSource;
import facet.core.spi.StorageException;
import facet.core.spi.TupleSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试装饰器的语义。
 *
 * <p>第一个用例最重要：<strong>不可重试的故障必须原样透出</strong>。否则约束违例、语法错误
 * 这类重试一万次结果相同的失败会被白白重试三次，把故障时施加的压力也翻三倍——而这恰恰
 * 发生在存储已经出问题的时候。
 *
 * <p>这个装饰器放在 {@code facet.core.spi} 而不是某个适配器里：重试只依赖
 * {@link StorageException#retryable()}，是端口层面的能力；放进适配器就要每个适配器写一遍。
 */
class RetryingSourceTest {

    private static final ObjectRef DOC = doc("readme");
    private static final AttrKey TITLE = AttrKey.text("title", AttrKey.Tier.SNAPSHOT);
    private static final SubjectRef.Principal ALICE = principal("alice");

    /** 不可重试的故障一次都不重试。 */
    @Test
    void permanentFailuresAreNotRetried() {
        var tuples = new FailNTimes(Integer.MAX_VALUE, false);
        var source = retrying(tuples);

        assertThrows(StorageException.class, () -> run(() -> source.subjects(DOC, VIEW)));

        assertEquals(1, tuples.calls.get(), "不可重试的故障不该产生第二次调用");
    }

    /** 瞬时故障重试到成功。 */
    @Test
    void transientFailuresAreRetriedUntilSuccess() {
        var tuples = new FailNTimes(2, true);
        var source = retrying(tuples);

        assertEquals(Set.of(), run(() -> source.subjects(DOC, VIEW)));
        assertEquals(3, tuples.calls.get(), "两次失败 + 一次成功");
    }

    /** 重试耗尽后抛出最后一次的异常，且仍然是可重试类型——让上层的 503 判断保持一致。 */
    @Test
    void exhaustionRethrowsTheLastFailure() {
        var tuples = new FailNTimes(Integer.MAX_VALUE, true);
        var source = retrying(tuples);

        var thrown = assertThrows(StorageException.class, () -> run(() -> source.subjects(DOC, VIEW)));

        assertTrue(thrown.retryable());
        assertEquals(4, tuples.calls.get(), "首次 + 三次重试");
    }

    /**
     * 属性读也在装饰范围内。
     *
     * <p>只包元组读会让属性读成为抖动时的短板：check 路径上两者交替发生，而在外面看起来
     * 是同一次判定。
     */
    @Test
    void attributeReadsAreAlsoRetried() {
        var attrs = new FlakyAttrs(2);
        var source = new RetryingSource(new MemoryTupleSource(), attrs, 3, Duration.ZERO);

        assertEquals("ok", run(() -> source.value(TITLE, DOC)));
        assertEquals(3, attrs.calls.get());
    }

    /**
     * {@code objects()} 的重试粒度是整次查询。
     *
     * <p>端口返回 {@code Stream}，惰性的话故障会在调用方迭代时才冒出来——那时已经出了
     * 装饰器的作用域，重试无从下手。
     */
    @Test
    void objectsIsMaterializedSoRetryCoversTheWholeQuery() {
        var tuples = new FailNTimes(1, true);
        var source = retrying(tuples);

        var found = run(() -> source.objects(ALICE, VIEW, new ObjectType("doc")).toList());

        assertEquals(0, found.size());
        assertEquals(2, tuples.calls.get());
    }

    /** 装饰之后判定结果不变：重试是可用性手段，不该碰语义。 */
    @Test
    void decoratingDoesNotChangeDecisions() {
        var backing = new MemoryTupleSource().write(TUPLES);
        var attrs = new MemoryAttrSource();
        var plain = new Checker(SCHEMA, backing, attrs);
        var retried = new Checker(SCHEMA, new RetryingSource(backing, attrs), attrs);

        for (var object : new ObjectRef[] {doc("deep"), doc("private"), doc("readme")}) {
            assertEquals(run(() -> plain.check(object, VIEW)).allowed(),
                    run(() -> retried.check(object, VIEW)).allowed(),
                    object.toString());
        }
    }

    /** 能力声明原样透传：装饰器不该改变存储声明的扇出上限或快照读能力。 */
    @Test
    void capsArePassedThrough() {
        var backing = new MemoryTupleSource(64);

        assertEquals(backing.caps(), new RetryingSource(backing, AttrSource.EMPTY).caps());
    }

    @Test
    void retryCountMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryingSource(new MemoryTupleSource(), AttrSource.EMPTY, -1, Duration.ZERO));
    }

    /** 要重试就必须给退避间隔：默认的"立即重试"必须是显式选择，不能是忘了填。 */
    @Test
    void positiveRetriesRequireABackoff() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryingSource(new MemoryTupleSource(), AttrSource.EMPTY, 1));
    }

    private static RetryingSource retrying(TupleSource tuples) {
        // 退避取 0：这些用例验证的是重试判断与次数，不该为了断言而真的睡几百毫秒
        return new RetryingSource(tuples, AttrSource.EMPTY, 3, Duration.ZERO);
    }

    private static <T> T run(java.util.function.Supplier<T> body) {
        return Ctx.run(Ctx.Request.of(ALICE), body);
    }

    /** 前 {@code failCount} 次调用失败，之后成功。 */
    private static final class FailNTimes implements TupleSource {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failCount;
        private final boolean retryable;

        FailNTimes(int failCount, boolean retryable) {
            this.failCount = failCount;
            this.retryable = retryable;
        }

        @Override
        public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
            return failOrReturn(Set.of());
        }

        @Override
        public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
            return failOrReturn(Stream.of());
        }

        @Override
        public Caps caps() {
            return new Caps(true, false, false, 1024);
        }

        private <T> T failOrReturn(T success) {
            int attempt = calls.incrementAndGet();
            if (attempt <= failCount) {
                throw new StorageException("模拟第 " + attempt + " 次失败", null, retryable);
            }
            return success;
        }
    }

    /** 前 {@code failCount} 次属性读失败，之后返回 {@code "ok"}。 */
    private static final class FlakyAttrs implements AttrSource {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failCount;

        FlakyAttrs(int failCount) {
            this.failCount = failCount;
        }

        @Override
        public Object value(AttrKey key, ObjectRef obj) {
            int attempt = calls.incrementAndGet();
            if (attempt <= failCount) {
                throw new StorageException("属性读第 " + attempt + " 次失败", null, true);
            }
            return "ok";
        }
    }
}

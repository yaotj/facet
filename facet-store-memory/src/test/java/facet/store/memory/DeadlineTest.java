package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.Deadline;
import facet.core.runtime.DeadlineExceededException;
import facet.core.eval.Expander;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.spi.TupleSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 墙钟期限。
 *
 * <p>它与工作预算是<strong>互补</strong>的，不是二选一：
 * <ul>
 *   <li>预算限总工作量、确定性，挡的是"这个形状会把存储打穿"；</li>
 *   <li>期限限墙钟、不确定性，挡的是"存储今天比平时慢十倍"——那种情况下工作量完全合规。</li>
 * </ul>
 *
 * <p>这里的用例都用"先让期限过去"的方式做到确定性，不依赖真实的慢存储。
 */
class DeadlineTest {

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Checker checker = new Checker(SCHEMA, tuples, attrs);

    @Test
    void noneIsUnbounded() {
        assertFalse(Deadline.NONE.bounded());
        assertFalse(Deadline.NONE.expired());
        assertNull(Deadline.NONE.remaining(), "无期限时 remaining 返回 null，逼调用方显式处理这一支");
    }

    @Test
    void deadlineMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Deadline.after(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Deadline.after(Duration.ofMillis(-1)));
    }

    /** 到期后 {@code expired} 为真，{@code remaining} 归零而不是变负。 */
    @Test
    void expiresAfterTheBudget() throws InterruptedException {
        var deadline = Deadline.after(Duration.ofMillis(1));
        Thread.sleep(5);

        assertTrue(deadline.expired());
        assertEquals(Duration.ZERO, deadline.remaining());
    }

    /** {@code clamp} 取两者较小值：期限不该放宽一个更紧的既有超时。 */
    @Test
    void clampTakesTheSmaller() {
        var generous = Deadline.after(Duration.ofSeconds(60));

        assertEquals(Duration.ofSeconds(5), generous.clamp(Duration.ofSeconds(5)));
        assertTrue(generous.clamp(Duration.ofSeconds(600)).compareTo(Duration.ofSeconds(61)) < 0,
                "期限比超时短时应当被收紧到剩余时间");
        assertEquals(Duration.ofSeconds(5), Deadline.NONE.clamp(Duration.ofSeconds(5)));
    }

    /**
     * 期限到了抛异常，不是判 deny。
     *
     * <p>和预算耗尽一样：结论是"不知道"，落成 deny 就是把一次"太慢"读成"确实无权限"。
     */
    @Test
    void expiryThrowsRatherThanDenies() throws InterruptedException {
        var request = Ctx.Request.of(principal("alice")).withDeadline(Duration.ofMillis(1));
        Thread.sleep(5);

        assertThrows(DeadlineExceededException.class,
                () -> Ctx.run(request, () -> checker.check(doc("deep"), VIEW)));
    }

    /** 展开路径同样受期限约束：它和 check 共用 Trail 上的那一个检查点。 */
    @Test
    void expansionRespectsTheDeadline() throws InterruptedException {
        var expander = new Expander(SCHEMA, tuples, attrs);
        var request = Ctx.Request.of(principal("alice")).withDeadline(Duration.ofMillis(1));
        Thread.sleep(5);

        assertThrows(DeadlineExceededException.class,
                () -> Ctx.run(request, () -> expander.subjects(doc("readme"), VIEW, Cursor.START, 100)));
    }

    /**
     * 期限按<strong>请求</strong>算，不是按每次判定。
     *
     * <p>这一点和工作预算相反，而且必须相反：调用方的 SLA 在 HTTP 响应上，一次批量判定
     * 二百个对象也只有那一个响应。做成每判定一份的话，批量越大总时长越没有上限。
     */
    @Test
    void theDeadlineSpansTheWholeRequest() throws InterruptedException {
        var request = Ctx.Request.of(principal("alice")).withDeadline(Duration.ofMillis(1));
        Thread.sleep(5);

        assertThrows(DeadlineExceededException.class,
                () -> Ctx.run(request, () -> checker.checkAll(
                        List.of(doc("deep"), doc("readme")), VIEW)));
    }

    /** 期限宽裕时不影响任何结论：它是兜底，不改语义。 */
    @Test
    void generousDeadlineDoesNotChangeDecisions() {
        var request = Ctx.Request.of(principal("alice")).withDeadline(Duration.ofSeconds(30));

        assertTrue(assertDoesNotThrow(
                () -> Ctx.run(request, () -> checker.check(doc("deep"), VIEW))).allowed());
    }

    /** 默认不设期限：墙钟上限属于部署决策，库不替使用方选。 */
    @Test
    void noDeadlineByDefault() {
        assertFalse(Ctx.Request.of(principal("alice")).deadline().bounded());
    }

    /** 上下文未绑定时 {@code Ctx.deadline()} 返回 NONE：建表、回收这类运维操作不在任何请求里。 */
    @Test
    void deadlineOutsideARequestIsNone() {
        assertFalse(Ctx.deadline().bounded());
    }

    /**
     * 期限必须传导到会阻塞的地方才真的生效。
     *
     * <p>这里验证的是最关键的一条不变量：<strong>到期之后不再向存储发起新的调用</strong>。
     * 不检查的话，一个 200ms 的期限配着 10 秒的语句超时，最坏返回时间是 10.2 秒。
     */
    @Test
    void noNewStorageCallsAfterExpiry() throws InterruptedException {
        var counting = new CountingTuples(tuples);
        var slow = new Checker(SCHEMA, counting, attrs);
        var request = Ctx.Request.of(principal("alice")).withDeadline(Duration.ofMillis(1));
        Thread.sleep(5);

        assertThrows(DeadlineExceededException.class,
                () -> Ctx.run(request, () -> slow.check(doc("deep"), VIEW)));
        assertEquals(0, counting.calls, "第一个求值节点就该拦住，一次存储调用都不该发出");
    }

    /** 数一下存储被调了几次。 */
    private static final class CountingTuples implements TupleSource {

        private final MemoryTupleSource backing;
        private int calls;

        CountingTuples(MemoryTupleSource backing) {
            this.backing = backing;
        }

        @Override
        public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
            calls++;
            return backing.subjects(obj, rel);
        }

        @Override
        public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
            calls++;
            return backing.objects(subject, rel, type);
        }

        @Override
        public Caps caps() {
            return backing.caps();
        }
    }
}

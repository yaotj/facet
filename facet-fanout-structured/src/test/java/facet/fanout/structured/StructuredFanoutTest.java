package facet.fanout.structured;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectType;
import facet.core.ir.SubjectRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化并发扇出的语义。
 *
 * <p>第三个用例是这个模块存在的理由之一：{@code ScopedValue} 自动继承到 fork 出的子任务，
 * 所以内核的六个算子分支不必把上下文塞进签名。这条如果不成立，整套 {@code Ctx} 设计就要重做。
 */
class StructuredFanoutTest {

    private final StructuredFanout fanout = new StructuredFanout();

    @Test
    void allReturnsEveryResult() throws Exception {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);

        assertEquals(List.of(1, 2, 3), fanout.all(tasks));
    }

    @Test
    void firstMatchReturnsCompletedResultsIncludingTheHit() throws Exception {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);

        var results = fanout.firstMatch(tasks, value -> value == 2);

        assertTrue(results.contains(2), results::toString);
    }

    @Test
    void scopedValueIsInheritedByForkedTasks() {
        var principal = new SubjectRef.Principal(new ObjectType("user"), "alice");

        var seen = Ctx.run(Ctx.Request.of(principal), () -> {
            try {
                return fanout.all(List.<Callable<SubjectRef>>of(() -> Ctx.current().principal()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(List.of(principal), seen);
    }

    /**
     * 没有命中时，失败分支必须浮出来。
     *
     * <p>{@code allUntil} 不抛子任务异常，只 filter SUCCESS 就等于把一次数据库故障
     * 静默变成"这条路没通"——授权判定里这是最危险的一类降级。
     */
    @Test
    void failureSurfacesWhenNothingMatched() {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> {
            throw new IllegalStateException("存储故障");
        });

        var thrown = assertThrows(IllegalStateException.class,
                () -> fanout.firstMatch(tasks, value -> value == 99));

        assertEquals("存储故障", thrown.getMessage());
    }

    /** 但命中优先于失败：命中是单调的，此时抛异常会把本该 allow 的请求变成 500。 */
    @Test
    void hitWinsOverFailure() throws Exception {
        List<Callable<Integer>> tasks = List.of(() -> {
            throw new IllegalStateException("存储故障");
        }, () -> 42);

        var results = fanout.firstMatch(tasks, value -> value == 42);

        assertTrue(results.contains(42), results::toString);
    }

    @Test
    void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new StructuredFanout(Duration.ZERO));
    }

    @Test
    void concurrencyMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new StructuredFanout(Duration.ofSeconds(1), 0));
    }

    /**
     * 并发上限必须真的封顶。
     *
     * <p>虚拟线程廉价，但每个分支背后是一条数据库连接，而递归会让扇出逐层叠加——不封顶时
     * 一次 check 就能把连接池抽干，那时故障面是整个进程而不是这一个请求。断言的是并发<strong>峰值</strong>：
     * 只断言"结果都回来了"的话，信号量被误删也一样能通过。
     */
    @Test
    void concurrencyIsCapped() throws Exception {
        var limited = new StructuredFanout(Duration.ofSeconds(10), 2);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var tasks = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(Duration.ofMillis(20));
                running.decrementAndGet();
                return 1;
            });
        }

        assertEquals(16, limited.all(tasks).size());
        assertTrue(peak.get() <= 2, "并发峰值 " + peak.get() + " 超过上限 2");
    }

    /**
     * 超时必须真的生效。
     *
     * <p>一条卡住的查询会让 {@code join()} 永久等待，连带请求线程和整棵子任务树；
     * 断言的是"没有等到任务自己结束"，而不是某个具体异常类型——
     * preview API 的异常类型换过几轮，把测试钉在类型上只会让它变脆。
     */
    @Test
    void slowTasksAreCutOffByTimeout() {
        var impatient = new StructuredFanout(Duration.ofMillis(100));
        List<Callable<Integer>> tasks = List.of(() -> {
            Thread.sleep(Duration.ofSeconds(30));
            return 1;
        });

        long started = System.nanoTime();
        assertThrows(Exception.class, () -> impatient.all(tasks));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.toSeconds() < 5, "没有在超时窗口内中断，实际耗时 " + elapsed);
    }
}

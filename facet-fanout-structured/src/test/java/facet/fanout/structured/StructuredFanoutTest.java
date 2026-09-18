package facet.fanout.structured;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectType;
import facet.core.ir.SubjectRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

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
}

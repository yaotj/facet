package facet.fanout.structured;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.Explains;
import facet.core.spi.Fanout;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.RandomScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 并行扇出下的求值正确性。
 *
 * <p>{@code Trail} 用持久化链表而不是共享的"正在求值"集合，理由就是并行：共享集合会把
 * 两个兄弟分支同时求值同一个 key 误判成环，得到一个假的 deny。这个类是那个设计决策的验证。
 *
 * <p><strong>只比对结论，不比对判定树。</strong>并行 {@code firstMatch} 一旦命中就取消其余
 * 分支，哪些分支来得及完成取决于调度，所以 explain 在并行下天然不确定。golden file 快照
 * 必须用顺序实现——这不是缺陷，是并行的代价，写在这里以免有人拿并行实现去对基线。
 */
class ParallelEvaluationTest {

    private static final int SEEDS = 40;

    @Test
    void parallelAgreesWithSequential() {
        var parallel = new StructuredFanout();

        for (long seed = 0; seed < SEEDS; seed++) {
            var scenario = RandomScenario.of(seed);
            var tuples = new MemoryTupleSource().write(scenario.tuples());
            var attrs = new MemoryAttrSource();
            var sequential = new Checker(scenario.schema(), tuples, attrs, Fanout.SEQUENTIAL);
            var concurrent = new Checker(scenario.schema(), tuples, attrs, parallel);

            for (var subject : scenario.subjects()) {
                var expected = decisions(scenario, sequential, subject);
                var actual = decisions(scenario, concurrent, subject);
                assertEquals(expected, actual, "seed=" + seed + " subject=" + subject);
            }
        }
    }

    /**
     * 并行下不能出现环剪枝。
     *
     * <p>随机场景里的 folder 层级是无环的，所以任何 {@code CYCLE-CUT} 都是共享状态导致的
     * 误报——而误报的后果是一个本该 allow 的请求变成 deny。
     */
    @Test
    void parallelNeverReportsFalseCycles() {
        var parallel = new StructuredFanout();

        for (long seed = 0; seed < SEEDS; seed++) {
            var scenario = RandomScenario.of(seed);
            var tuples = new MemoryTupleSource().write(scenario.tuples());
            var checker = new Checker(scenario.schema(), tuples, new MemoryAttrSource(), parallel);

            for (var subject : scenario.subjects()) {
                var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
                for (var doc : scenario.docs()) {
                    var rendered = Ctx.run(request,
                            () -> Explains.render(checker.check(doc, RandomScenario.VIEW).explain()));
                    assertFalse(rendered.contains("CYCLE-CUT"),
                            "seed=" + seed + " 出现了假环剪枝:\n" + rendered);
                }
            }
        }
    }

    private static List<Boolean> decisions(RandomScenario.Generated scenario,
                                           Checker checker, facet.core.ir.SubjectRef subject) {
        var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
        var out = new ArrayList<Boolean>(scenario.docs().size());
        scenario.docs().forEach(doc ->
                out.add(Ctx.run(request, () -> checker.check(doc, RandomScenario.VIEW)).allowed()));
        return out;
    }
}

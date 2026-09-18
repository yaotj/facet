package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.EvalException;
import facet.core.eval.Schema;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.spi.Metrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个观测量都必须真的落下来。
 *
 * <p>观测挂点的测试价值和别处不同：这三个量对应的都是<strong>结果完全正确、只是慢或者只是
 * 快撞上限</strong>的那类退化。埋点自己坏掉不会让任何判定出错，所以没有断言看着它，
 * 它失效之后唯一的症状是生产上再也看不到这些数——而那时没人知道是从哪一版开始的。
 */
class MetricsTest {

    private static final Rel READ = new Rel("read");
    private static final AttrKey EMPLOYED = AttrKey.bool("employed", AttrKey.Tier.EXTERNAL);

    /** 带 EXTERNAL 属性的 schema：批量大小这个量只有在有属性要预取时才有意义。 */
    private static final Schema EXTERNAL = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
            VIEWER, Schema.tuples(VIEWER),
            READ, Schema.computed(new Perm.Guarded(new Perm.Direct(VIEWER),
                    new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(EMPLOYED),
                            new Cond.Term.Lit(true))), false)))));

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Recording sink = new Recording();

    /** 一次 check 一条判定记录，关系名与结论都要对——延迟分布是按关系分组看的。 */
    @Test
    void oneDecisionPerCheck() {
        var checker = new Checker(SCHEMA, tuples, attrs);

        run(principal("alice"), () -> checker.check(doc("deep"), VIEW));

        assertEquals(List.of("view=true"), sink.decisions);
        assertTrue(sink.elapsed.getFirst() > 0, "耗时应当为正");
    }

    @Test
    void bulkReportsOneDecisionPerObject() {
        var checker = new Checker(SCHEMA, tuples, attrs);

        run(principal("bob"), () -> checker.checkAll(
                List.of(doc("deep"), doc("private"), doc("readme")), VIEW));

        assertEquals(List.of("view=false", "view=true", "view=false"), sink.decisions);
    }

    /** 层级每跳一层都上报一次宽度：热点对象要在撞上限之前先在这里现形。 */
    @Test
    void everyHopReportsItsWidth() {
        var checker = new Checker(SCHEMA, tuples, attrs);

        run(principal("alice"), () -> checker.check(doc("deep"), VIEW));

        assertEquals(List.of("Through(parent)=1", "Through(parent)=1"), sink.fanouts);
    }

    /** userset 展开也是一次扇出：几万人的组在这里现形，而不是在超时里。 */
    @Test
    void usersetExpansionIsAlsoAFanout() {
        var checker = new Checker(SCHEMA, tuples, attrs);

        run(principal("carol"), () -> checker.check(doc("readme"), VIEW));

        assertTrue(sink.fanouts.contains("Userset 展开=1"), sink.fanouts::toString);
    }

    /**
     * 先上报再判上限。
     *
     * <p>反过来写的话，撞上限的那次扇出恰好是唯一不会被记录的一次——而它正是最需要留痕的一次。
     */
    @Test
    void widthIsReportedEvenWhenTheLimitRejects() {
        var wide = new MemoryTupleSource(1).write(
                Tuple.of(doc("wide"), PARENT, folder("eng")),
                Tuple.of(doc("wide"), PARENT, folder("team")));
        var narrow = new Checker(SCHEMA, wide, attrs);

        assertThrows(EvalException.class,
                () -> run(principal("alice"), () -> narrow.check(doc("wide"), VIEW)));
        assertEquals(List.of("Through(parent)=2"), sink.fanouts);
    }

    /** 批量大小就是这一批的对象数；它长期是 1 就说明预取没生效。 */
    @Test
    void attributeBatchSizeIsReported() {
        var checker = new Checker(EXTERNAL, tuples, attrs);
        var docs = List.of(doc("deep"), doc("private"), doc("readme"), doc("spec"));
        docs.forEach(object -> attrs.put(object, EMPLOYED, true));

        run(principal("alice"), () -> checker.checkAll(docs, READ));

        assertEquals(List.of("employed×4"), sink.batches);
    }

    private <T> T run(SubjectRef subject, Supplier<T> body) {
        return Ctx.run(Ctx.Request.of(subject).withMetrics(sink), body);
    }

    /** 把三个量摊平成字符串：断言读起来就是"上报了什么"。 */
    private static final class Recording implements Metrics {

        private final List<String> decisions = new ArrayList<>();
        private final List<Long> elapsed = new ArrayList<>();
        private final List<String> fanouts = new ArrayList<>();
        private final List<String> batches = new ArrayList<>();

        @Override
        public void decision(Rel relation, boolean allowed, long elapsedNanos) {
            decisions.add(relation.name() + "=" + allowed);
            elapsed.add(elapsedNanos);
        }

        @Override
        public void fanout(String operator, int width) {
            fanouts.add(operator + "=" + width);
        }

        @Override
        public void attributeBatch(AttrKey key, int size) {
            batches.add(key.name() + "×" + size);
        }
    }
}

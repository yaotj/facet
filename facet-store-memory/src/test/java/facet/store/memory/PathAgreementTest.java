package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Explains;
import facet.core.eval.Planner;
import facet.core.eval.Validator;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.Plan;
import facet.core.ir.SubjectRef;
import facet.core.eval.Keys;
import facet.testkit.RandomScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 两条求值路径的差分测试。
 *
 * <p>断言的是这个项目最核心的一条不变量：对任意 schema 与任意数据，
 * {@code lookupResources} 的结果集必须<strong>恰好等于</strong>逐个 {@code check} 为 allow
 * 的那些对象。check 是解释器、反查是编译器，两者方向相反、代码不共用——一致性只能靠这样断言。
 *
 * <p>不需要 Docker，因此每次构建都跑。种子固定，失败时打印种子即可复现，
 * 因为 {@code RandomScenario} 是确定性的。
 */
class PathAgreementTest {

    /** 每个种子生成一份 schema + 一份数据。跑完只需几百毫秒，所以数量取得比"够用"更多。 */
    private static final int SEEDS = 200;

    @Test
    void lookupResourcesEqualsFilteringByCheck() {
        for (long seed = 0; seed < SEEDS; seed++) {
            var scenario = RandomScenario.of(seed);
            Validator.validate(scenario.schema());

            var tuples = new MemoryTupleSource().write(scenario.tuples());
            var attrs = new MemoryAttrSource();
            var checker = new Checker(scenario.schema(), tuples, attrs);
            var planner = new Planner(scenario.schema(), tuples.caps());
            var executor = new MemoryPlanExecutor(tuples, attrs);

            for (var subject : scenario.subjects()) {
                var byCheck = sorted(filterByCheck(scenario, checker, subject));
                var byLookup = sorted(lookupAll(scenario, planner, executor, subject));

                if (!byCheck.equals(byLookup)) {
                    fail(diagnosis(scenario, subject, byCheck, byLookup, checker));
                }
            }
        }
    }

    /** 递归定义的场景里，反查必须能翻出多层继承下来的对象——顺带确认场景真的有内容。 */
    @Test
    void scenariosAreNotTriviallyEmpty() {
        int nonEmpty = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            var scenario = RandomScenario.of(seed);
            var tuples = new MemoryTupleSource().write(scenario.tuples());
            var attrs = new MemoryAttrSource();
            var checker = new Checker(scenario.schema(), tuples, attrs);
            for (var subject : scenario.subjects()) {
                if (!filterByCheck(scenario, checker, subject).isEmpty()) {
                    nonEmpty++;
                }
            }
        }
        // 阈值取得很松：只是排除"生成器产出空数据，断言全都恒真"这种假通过
        assertEquals(true, nonEmpty > SEEDS / 2,
                "只有 " + nonEmpty + " 个主体拿到了非空结果，生成器可能没在产生有效授权");
    }

    private static List<ObjectRef> filterByCheck(RandomScenario.Generated scenario,
                                                 Checker checker, SubjectRef subject) {
        var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
        return Ctx.run(request, () -> scenario.docs().stream()
                .filter(doc -> checker.check(doc, RandomScenario.VIEW).allowed())
                .toList());
    }

    /** 翻完所有页，页大小刻意取 2 以逼出游标边界。 */
    private static List<ObjectRef> lookupAll(RandomScenario.Generated scenario, Planner planner,
                                             MemoryPlanExecutor executor, SubjectRef subject) {
        var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
        var found = new ArrayList<ObjectRef>();
        var cursor = Cursor.START;
        while (true) {
            Plan plan = planner.plan(RandomScenario.DOC, RandomScenario.VIEW, cursor, 2);
            var page = Ctx.run(request, () -> executor.execute(plan).toList());
            if (page.isEmpty()) {
                return found;
            }
            found.addAll(page);
            cursor = Cursor.of(page.getLast());
        }
    }

    private static List<ObjectRef> sorted(List<ObjectRef> refs) {
        return refs.stream().sorted(Comparator.comparing(Cursor::keyOf, Keys.ORDER)).toList();
    }

    /** 失败信息要够用来定位：种子、双方结果、以及分歧对象的判定树。 */
    private static String diagnosis(RandomScenario.Generated scenario, SubjectRef subject,
                                    List<ObjectRef> byCheck, List<ObjectRef> byLookup,
                                    Checker checker) {
        var onlyCheck = new ArrayList<>(byCheck);
        onlyCheck.removeAll(byLookup);
        var onlyLookup = new ArrayList<>(byLookup);
        onlyLookup.removeAll(byCheck);

        var report = new StringBuilder()
                .append("两条路径分歧 seed=").append(scenario.seed())
                .append(" subject=").append(subject).append('\n')
                .append("只有 check 认可: ").append(onlyCheck).append('\n')
                .append("只有反查认可: ").append(onlyLookup).append('\n')
                .append("context=").append(scenario.context()).append('\n');

        var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
        for (var doc : onlyCheck.isEmpty() ? onlyLookup : onlyCheck) {
            var decision = Ctx.run(request, () -> checker.check(doc, RandomScenario.VIEW));
            report.append("--- ").append(Cursor.keyOf(doc))
                    .append(" allowed=").append(decision.allowed()).append('\n')
                    .append(Explains.render(decision.explain()));
        }
        return report.toString();
    }
}

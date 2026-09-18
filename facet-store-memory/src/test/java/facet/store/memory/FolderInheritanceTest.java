package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Planner;
import facet.core.eval.Validator;
import facet.core.ir.Cursor;
import facet.core.ir.Perm;
import facet.core.ir.Plan;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.testkit.DecisionMatrix;
import facet.core.eval.Explains;
import facet.testkit.Golden;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.FOLDER;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.VIEW_MFA;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * folder → folder → doc 多层继承：两条求值路径的一致性基准。
 *
 * <p>同一份 schema，check 与 lookupResources 必须给出一致答案。这套断言的主要价值不是
 * 覆盖率，而是把三个设计决策钉住：递归在环境里（{@code Ref}）、两条路方向相反
 * （{@code Through} → {@code ExpandUp}）、递归反查是传递闭包（{@code ExpandUpClosure}）。
 */
class FolderInheritanceTest {

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Checker checker = new Checker(SCHEMA, tuples, attrs);
    private final Planner planner = new Planner(SCHEMA, tuples.caps());
    private final MemoryPlanExecutor executor = new MemoryPlanExecutor(tuples, attrs);

    @Test
    void schemaPassesLoadTimeValidation() {
        Validator.validate(SCHEMA);
    }

    @Test
    void singleHopInheritance() {
        var decision = run(principal("alice"), () -> checker.check(doc("readme"), VIEW));

        assertTrue(decision.allowed());
        assertEquals("""
                AnyOf
                  MISS doc:readme#viewer
                  Through(parent)
                    Ref(view)
                      AnyOf
                        HIT folder:eng#viewer
                """, Explains.render(decision.explain()));
    }

    /** 两层 folder：深度由数据决定，Perm 树没有变深。 */
    @Test
    void multiLevelInheritance() {
        var decision = run(principal("alice"), () -> checker.check(doc("deep"), VIEW));

        assertTrue(decision.allowed());
        assertEquals("""
                AnyOf
                  MISS doc:deep#viewer
                  Through(parent)
                    Ref(view)
                      AnyOf
                        MISS folder:team#viewer
                        Through(parent)
                          Ref(view)
                            AnyOf
                              HIT folder:eng#viewer
                """, Explains.render(decision.explain()));
    }

    @Test
    void usersetExpandsGroupMembership() {
        var decision = run(principal("carol"), () -> checker.check(doc("spec"), VIEW));

        assertTrue(decision.allowed());
        assertEquals("""
                AnyOf
                  MISS doc:spec#viewer
                  Through(parent)
                    Ref(view)
                      AnyOf
                        Direct(viewer)
                          HIT group:eng#member
                """, Explains.render(decision.explain()));
    }

    @Test
    void unrelatedSubjectIsDenied() {
        assertFalse(run(principal("bob"), () -> checker.check(doc("readme"), VIEW)).allowed());
    }

    @Test
    void minusIsMonotonic() {
        var decision = run(principal("alice"), () -> checker.check(doc("readme"), EDIT));

        assertFalse(decision.allowed());
        assertEquals("""
                DENIED-BY
                  HIT doc:readme#banned
                """, Explains.render(decision.explain()));
    }

    @Test
    void guardedConditionGatesOnContextAttribute() {
        var withMfa = Ctx.Request.of(principal("alice")).withContextAttrs(Map.of("mfa", "true"));
        assertTrue(Ctx.run(withMfa, () -> checker.check(doc("readme"), VIEW_MFA)).allowed());

        assertFalse(run(principal("alice"), () -> checker.check(doc("readme"), VIEW_MFA)).allowed());
    }

    /** Through → ExpandUp 是方向翻转，Ref 自递归 → ExpandUpClosure 是递归 CTE。 */
    @Test
    void plannerCompilesRecursionIntoTransitiveClosure() {
        var plan = planner.plan(DOC, VIEW, Cursor.START, 10);

        assertEquals(new Plan.Page(
                new Plan.Union(List.of(
                        new Plan.ScanReverse(VIEWER, DOC),
                        new Plan.ExpandUp(
                                new Plan.Union(List.of(
                                        new Plan.ScanReverse(VIEWER, FOLDER),
                                        new Plan.ExpandUpClosure(
                                                new Plan.ScanReverse(VIEWER, FOLDER), PARENT, FOLDER))),
                                PARENT, DOC))),
                Cursor.START, 10), plan);
    }

    @Test
    void lookupResourcesAgreesWithCheck() {
        var plan = planner.plan(DOC, VIEW, Cursor.START, 10);
        var inherited = List.of(doc("deep"), doc("readme"), doc("spec"));

        assertEquals(inherited, run(principal("alice"), () -> executor.execute(plan).toList()));
        assertEquals(inherited, run(principal("carol"), () -> executor.execute(plan).toList()));
        assertEquals(List.of(doc("private")),
                run(principal("bob"), () -> executor.execute(plan).toList()));
    }

    @Test
    void cursorPaginationWalksTheResultSet() {
        var first = run(principal("alice"),
                () -> executor.execute(planner.plan(DOC, VIEW, Cursor.START, 1)).toList());
        assertEquals(List.of(doc("deep")), first);

        var next = Cursor.of(first.getLast());
        assertEquals(List.of(doc("readme")),
                run(principal("alice"),
                        () -> executor.execute(planner.plan(DOC, VIEW, next, 1)).toList()));
    }

    /** 数据成环：folder 层级互指。递归接通之后，环检测防的是这个而不只是 userset。 */
    @Test
    void dataCycleInHierarchyIsCut() {
        var cyclic = new MemoryTupleSource().write(
                Tuple.of(folder("a"), PARENT, folder("b")),
                Tuple.of(folder("b"), PARENT, folder("a")));

        var decision = Ctx.run(Ctx.Request.of(principal("dave")),
                () -> new Checker(SCHEMA, cyclic, attrs).check(folder("a"), VIEW));

        assertFalse(decision.allowed());
        var rendered = Explains.render(decision.explain());
        assertTrue(rendered.contains("CYCLE-CUT folder:a"), rendered);
    }

    @Test
    void evaluationOutsideScopedValueFails() {
        assertThrows(IllegalStateException.class, () -> checker.check(doc("readme"), VIEW));
    }

    /** 一致性坐标从上下文取，适配器不支持就必须拒绝，不能静默读最新。 */
    @Test
    void memoryStoreRejectsSnapshotRead() {
        var pinned = Ctx.Request.of(principal("alice")).at(new Revision(42));

        assertThrows(UnsupportedOperationException.class,
                () -> Ctx.run(pinned, () -> checker.check(doc("readme"), VIEW)));
    }

    /** 完整判定矩阵落文件：策略改动的影响面直接体现在 git diff 上。 */
    @Test
    void decisionMatrixSnapshot() {
        var matrix = DecisionMatrix.render(
                List.of(principal("alice"), principal("bob"), principal("carol")),
                List.of(doc("deep"), doc("private"), doc("readme"), doc("spec")),
                List.of(VIEW, EDIT, VIEW_MFA),
                Map.of("mfa", "true"),
                checker::check);

        Golden.verify("memory-decision-matrix.txt", matrix);
    }

    /** {@code Perm} 也能直接求值，不必经过 schema——DSL 前端的输出就是这种形态。 */
    @Test
    void rawPermCanBeEvaluated() {
        var decision = run(principal("bob"),
                () -> checker.check(new Perm.Direct(VIEWER), doc("private")));

        assertTrue(decision.allowed());
    }

    private <T> T run(SubjectRef.Principal subject, Supplier<T> body) {
        return Ctx.run(Ctx.Request.of(subject), body);
    }
}

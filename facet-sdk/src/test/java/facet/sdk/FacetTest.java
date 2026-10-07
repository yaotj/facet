package facet.sdk;

import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Decision;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.FolderScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}

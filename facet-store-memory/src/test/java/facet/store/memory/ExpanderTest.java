package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.EvalException;
import facet.core.eval.Expander;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEW_MFA;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展开路径：谁能对这个对象做这件事。
 *
 * <p>最后一个用例才是这条路径的正确性依据：<strong>展开结果必须与逐个 check 完全一致</strong>。
 * 展开走的是正向端口、按集合运算求解，check 走的是短路求值，两套代码算同一个语义——
 * 不把它们对齐，"这份文档共享给了谁"就会和"我能不能看这份文档"给出互相矛盾的答案，
 * 而这种矛盾在权限管理界面上表现为"页面上没有他，但他确实能打开"。
 */
class ExpanderTest {

    private static final List<ObjectRef> DOCS =
            List.of(doc("deep"), doc("private"), doc("readme"), doc("spec"));

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Expander expander = new Expander(SCHEMA, tuples, attrs);
    private final Checker checker = new Checker(SCHEMA, tuples, attrs);

    /** 直接授权与 userset 授权都要落到具体的人：alice 是 folder 的 viewer，carol 只经 group。 */
    @Test
    void usersetsAreFlattenedToPrincipals() {
        assertEquals(List.of(principal("alice"), principal("carol")),
                subjects(doc("readme"), VIEW, Map.of()));
    }

    /** 层级深度由数据决定，展开也一样：doc:deep 要跳两层 folder。 */
    @Test
    void expansionFollowsTheWholeHierarchy() {
        assertEquals(List.of(principal("alice"), principal("carol")),
                subjects(doc("deep"), VIEW, Map.of()));
    }

    @Test
    void directGrantIsNotLeakedToOtherObjects() {
        assertEquals(List.of(principal("bob")), subjects(doc("private"), VIEW, Map.of()));
    }

    /**
     * {@code Minus} 在具体主体这一粒度上做差。
     *
     * <p>alice 同时是 readme 的 editor 和 banned，所以结果必须是空集。这正是 userset
     * 必须被展开成 principal 的理由：留在 userset 粒度上，"组成员减去被禁用的人"无从计算。
     */
    @Test
    void minusRemovesDeniedPrincipals() {
        assertTrue(subjects(doc("readme"), EDIT, Map.of()).isEmpty());
    }

    /** 条件按给定上下文求值：换个上下文再问一次，就是"如果没过 MFA 呢"。 */
    @Test
    void guardedExpansionDependsOnTheGivenContext() {
        assertTrue(subjects(doc("readme"), VIEW_MFA, Map.of()).isEmpty());
        assertEquals(List.of(principal("alice"), principal("carol")),
                subjects(doc("readme"), VIEW_MFA, Map.of("mfa", "true")));
    }

    /**
     * 数据成环时返回已收集到的部分，而不是抛异常。
     *
     * <p>展开是"收集"语义：缺一部分结果好过让一次审计查询失败。环本身在 check 路径上
     * 已经会报成 CYCLE-CUT，不必在这里重复暴露。
     */
    @Test
    void dataCycleYieldsWhatWasCollected() {
        var cyclic = new MemoryTupleSource().write(
                Tuple.of(folder("a"), PARENT, folder("b")),
                Tuple.of(folder("b"), PARENT, folder("a")));
        var expanding = new Expander(SCHEMA, cyclic, attrs);

        assertTrue(Ctx.run(Ctx.Request.of(principal("dave")),
                () -> expanding.subjects(folder("a"), VIEW, Cursor.START, 100)).principals().isEmpty());
    }

    /** 展开的成本和扇出一样会失控，所以用同一个上限硬拒绝。 */
    @Test
    void expansionRespectsTheFanoutLimit() {
        var wide = new MemoryTupleSource(1).write(
                Tuple.of(doc("wide"), PARENT, folder("eng")),
                Tuple.of(doc("wide"), PARENT, folder("team")));
        var expanding = new Expander(SCHEMA, wide, attrs);

        assertThrows(EvalException.class, () -> Ctx.run(Ctx.Request.of(principal("dave")),
                () -> expanding.subjects(doc("wide"), VIEW, Cursor.START, 100)));
    }

    @Test
    void expansionOutsideScopedValueFails() {
        assertThrows(IllegalStateException.class, () -> expander.subjects(doc("readme"), VIEW, Cursor.START, 100));
    }

    /**
     * 游标分页走完整个结果集，不漏不重。
     *
     * <p>排序与游标比较必须是<strong>同一个序</strong>——排序用 UTF-16、游标比较用字节序的话，
     * 含非 BMP 字符的 id 会让某一页被跳过或重复。这里一次取一个，逐页走到空。
     */
    @Test
    void cursorPaginationWalksTheWholeSet() {
        var request = Ctx.Request.of(principal("placeholder"));
        var walked = new java.util.ArrayList<SubjectRef.Principal>();
        var cursor = Cursor.START;
        while (true) {
            var page = cursor;
            var found = List.copyOf(
                    Ctx.run(request, () -> expander.subjects(doc("readme"), VIEW, page, 1)).principals());
            if (found.isEmpty()) {
                break;
            }
            walked.addAll(found);
            cursor = Cursor.of(found.getLast());
        }

        assertEquals(List.of(principal("alice"), principal("carol")), walked);
    }

    /** 没有上限就不给答案：结果集大小由数据决定，一次审计查询不该能拉出几十兆响应。 */
    @Test
    void limitMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> Ctx.run(Ctx.Request.of(principal("placeholder")),
                        () -> expander.subjects(doc("readme"), VIEW, Cursor.START, 0)));
    }

    /**
     * 两条路径的一致性：主体出现在展开结果里，当且仅当 check 放行。
     *
     * <p>四个主体 × 四个对象 × 三个关系，条件关系再乘两种上下文。
     */
    @Test
    void expansionAgreesWithCheck() {        var candidates = List.of(principal("alice"), principal("bob"),
                principal("carol"), principal("dave"));

        for (var context : List.of(Map.<String, Object>of(), Map.<String, Object>of("mfa", "true"))) {
            for (var relation : List.of(VIEW, EDIT, VIEW_MFA)) {
                for (var object : DOCS) {
                    var expanded = subjects(object, relation, context);
                    for (var candidate : candidates) {
                        boolean allowed = Ctx.run(
                                Ctx.Request.of(candidate).withContextAttrs(context),
                                () -> checker.check(object, relation)).allowed();

                        assertEquals(allowed, expanded.contains(candidate),
                                candidate + " 在 " + object + "#" + relation.name()
                                        + " 上：check=" + allowed + "，展开结果=" + expanded);
                    }
                }
            }
        }
    }

    /** 展开不针对某个主体，上下文里的 principal 只是占位。 */
    private List<SubjectRef.Principal> subjects(ObjectRef object, Rel relation,
                                                Map<String, Object> context) {
        var request = Ctx.Request.of(principal("placeholder")).withContextAttrs(context);
        return List.copyOf(
                Ctx.run(request, () -> expander.subjects(object, relation, Cursor.START, 100)).principals());
    }
}

package facet.store.pg;

import facet.core.eval.Planner;
import facet.core.ir.Cond;
import facet.core.ir.Cursor;
import facet.core.ir.Plan;
import facet.core.spi.TupleSource;
import facet.testkit.Golden;
import org.junit.jupiter.api.Test;

import java.util.List;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.FOLDER;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.VIEW_MFA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan → SQL 的离线断言。
 *
 * <p>这些 golden 文件是"反查可以整条下推"这个架构主张的证据。它们不需要数据库，因此
 * 每次构建都会跑；真实语义由 {@code PgConsistencyIT} 用 testcontainers 验证。
 */
class PlanSqlCompilerTest {

    private static final TupleSource.Caps FULL = new TupleSource.Caps(true, true, true, 1024);
    private final Planner planner = new Planner(SCHEMA, FULL);

    /** 递归层级：整棵计划一条语句，递归部分是 WITH RECURSIVE，没有应用层循环。 */
    @Test
    void recursiveHierarchyCompilesToOneStatement() {
        var query = PlanSqlCompiler.compile(planner.plan(DOC, VIEW, Cursor.START, 10));

        Golden.verify("sql-doc-view.sql", query.sql());
        Golden.verify("sql-doc-view.params.txt", render(query));
        assertEquals(1L, count(query.sql(), "WITH RECURSIVE facet_subject"), query.sql());
        assertEquals(1L, count(query.sql(), "WITH RECURSIVE cl"), query.sql());
    }

    /** Minus → EXCEPT。 */
    @Test
    void differenceCompilesToExcept() {
        var query = PlanSqlCompiler.compile(planner.plan(DOC, EDIT, Cursor.START, 10));

        assertTrue(query.sql().contains("EXCEPT"), query.sql());
        Golden.verify("sql-doc-edit.sql", query.sql());
    }

    /** Guarded(CONTEXT) → 绑定参数，不碰属性表。 */
    @Test
    void contextConditionBecomesBoundParameter() {
        var query = PlanSqlCompiler.compile(planner.plan(DOC, VIEW_MFA, Cursor.START, 10));

        assertTrue(query.params().contains(new Param.ContextAttr("mfa")), render(query));
        assertTrue(query.sql().contains("IS NOT DISTINCT FROM"), query.sql());
        Golden.verify("sql-doc-view-mfa.sql", query.sql());
    }

    /** 剩下两个算子：Intersect 与 SNAPSHOT 条件的属性子查询。 */
    @Test
    void intersectAndSnapshotFilterCompile() {
        var region = facet.core.ir.AttrKey.text("region", facet.core.ir.AttrKey.Tier.SNAPSHOT);
        var plan = new Plan.Page(
                new Plan.Filter(
                        new Plan.Intersect(List.of(
                                new Plan.ScanReverse(VIEWER, DOC),
                                new Plan.ExpandUp(new Plan.ScanReverse(VIEWER, FOLDER), PARENT, DOC))),
                        new Cond.Cmp(Cond.Op.EQ,
                                new Cond.Term.Attr(region),
                                new Cond.Term.Lit("eu"))),
                Cursor.START, 5);

        var query = PlanSqlCompiler.compile(plan);

        assertTrue(query.sql().contains("INTERSECT"), query.sql());
        assertTrue(query.sql().contains("FROM facet_attr a"), query.sql());
        Golden.verify("sql-intersect-snapshot.sql", query.sql());
    }

    /** 键游标必须钉住 C 排序规则，否则同一个游标在两个适配器上翻到不同位置。 */
    @Test
    void cursorKeyIsCollationPinned() {
        var query = PlanSqlCompiler.compile(planner.plan(DOC, VIEW, Cursor.START, 10));

        assertTrue(query.sql().contains("COLLATE \"C\""), query.sql());
    }

    /**
     * 编译缓存按计划形状而非分页值：游标进了缓存键，客户端只要不断变换游标
     * 就能让一个只读接口把内存吃光。
     */
    @Test
    void compilationCacheIgnoresPagingValues() {
        var executor = new PgPlanExecutor(() -> {
            throw new UnsupportedOperationException("编译不该碰数据库");
        });
        var first = executor.explainSql(planner.plan(DOC, VIEW, Cursor.START, 10));
        var second = executor.explainSql(planner.plan(DOC, VIEW, new Cursor("doc:zzz"), 3));

        assertSame(first, second);
    }

    private static String render(SqlQuery query) {
        var out = new StringBuilder();
        for (int i = 0; i < query.params().size(); i++) {
            out.append(i + 1).append(": ").append(query.params().get(i)).append('\n');
        }
        return out.toString();
    }

    private static long count(String haystack, String needle) {
        return haystack.lines().filter(line -> line.contains(needle)).count();
    }
}

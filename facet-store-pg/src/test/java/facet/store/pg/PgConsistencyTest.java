package facet.store.pg;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.DecisionMatrix;
import facet.testkit.Golden;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.USER;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.VIEW_MFA;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨适配器一致性。
 *
 * <p>同一份 schema、同一份元组，内存实现与 PG 实现必须给出<strong>逐字符相同</strong>的
 * 判定矩阵。只断言 ALLOW/DENY 不够——判定路径不同意味着两条实现对同一份数据的理解不同，
 * 那种分歧会在换存储时以"某个用户突然看不到某些资源"的形式暴露。
 *
 * <p>没有 Docker 时整类跳过：真实存储的验证不该成为构建的硬前提。
 */
@Testcontainers(disabledWithoutDocker = true)
class PgConsistencyTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Revision WRITTEN = new Revision(1);

    /**
     * 公开文档的通配授权，以及它的开/关坐标。
     *
     * <p>不写进共享 fixture：类内执行顺序不保证，而它会让反查多出一个对象，把那几条
     * "两个适配器结果逐字符相同"的断言变成偶发失败。用例里现写现撤，是为了让这份数据的
     * 影响面刚好等于用到它的那两个用例。
     */
    private static final Revision OPENED = new Revision(20);
    private static final Revision CLOSED = new Revision(21);
    private static final Tuple PUBLIC_VIEW =
            new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));

    private static PgTupleSource pgTuples;
    private static PgAttrSource pgAttrs;
    private static PgPlanExecutor pgExecutor;

    private static MemoryTupleSource memTuples;
    private static MemoryAttrSource memAttrs;
    private static MemoryPlanExecutor memExecutor;

    @BeforeAll
    static void setUp() {
        // 直接用 DriverManager 而不是 PGSimpleDataSource：Connections 只要一条连接，
        // 引入 DataSource 会顺带把 javax.naming 拖进模块图。
        Connections connections = () -> java.sql.DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());

        pgTuples = new PgTupleSource(connections);
        pgTuples.migrate();
        pgTuples.write(WRITTEN, TUPLES);
        pgAttrs = new PgAttrSource(connections);
        pgExecutor = new PgPlanExecutor(connections);

        memTuples = new MemoryTupleSource().write(TUPLES);
        memAttrs = new MemoryAttrSource();
        memExecutor = new MemoryPlanExecutor(memTuples, memAttrs);
    }

    @Test
    void capsDeclareSnapshotRead() {
        assertTrue(pgTuples.caps().snapshotRead());
        assertTrue(pgTuples.caps().recursiveQuery());
        assertFalse(memTuples.caps().snapshotRead());
    }

    /** check 路径：判定树逐字符一致。 */
    @Test
    void decisionMatrixIsIdenticalAcrossAdapters() {
        var subjects = List.<SubjectRef>of(principal("alice"), principal("bob"), principal("carol"));
        var objects = List.of(doc("deep"), doc("private"), doc("readme"), doc("spec"));
        var relations = List.of(VIEW, EDIT, VIEW_MFA);
        var context = Map.<String, Object>of("mfa", "true");

        var memory = DecisionMatrix.render(subjects, objects, relations, context,
                new Checker(SCHEMA, memTuples, memAttrs)::check);
        var postgres = DecisionMatrix.render(subjects, objects, relations, context,
                new Checker(SCHEMA, pgTuples, pgAttrs)::check);

        assertEquals(memory, postgres);
        Golden.verify("pg-decision-matrix.txt", postgres);
    }

    /** 反查路径：一条 SQL 的结果与内存里的集合运算一致。 */
    @Test
    void lookupResourcesIsIdenticalAcrossAdapters() {
        var plan = new Planner(SCHEMA, pgTuples.caps()).plan(DOC, VIEW, Cursor.START, 10);

        for (var who : List.of("alice", "bob", "carol")) {
            var request = Ctx.Request.of(principal(who));
            var memory = Ctx.run(request, () -> memExecutor.execute(plan).toList());
            var postgres = Ctx.run(request, () -> pgExecutor.execute(plan).toList());
            assertEquals(memory, postgres, who);
        }
    }

    /** 多层继承在 SQL 侧由 WITH RECURSIVE 完成，结果必须覆盖两级 folder 下的 doc。 */
    @Test
    void recursiveClosureFindsDeepDocument() {
        var plan = new Planner(SCHEMA, pgTuples.caps()).plan(DOC, VIEW, Cursor.START, 10);

        var result = Ctx.run(Ctx.Request.of(principal("alice")),
                () -> pgExecutor.execute(plan).toList());

        assertEquals(List.of(doc("deep"), doc("readme"), doc("spec")), result);
    }

    @Test
    void paginationIsIdenticalAcrossAdapters() {
        var planner = new Planner(SCHEMA, pgTuples.caps());
        var request = Ctx.Request.of(principal("alice"));

        var firstPage = planner.plan(DOC, VIEW, Cursor.START, 2);
        var memory = Ctx.run(request, () -> memExecutor.execute(firstPage).toList());
        var postgres = Ctx.run(request, () -> pgExecutor.execute(firstPage).toList());
        assertEquals(memory, postgres);
        assertEquals(2, postgres.size());

        var next = planner.plan(DOC, VIEW, Cursor.of(postgres.getLast()), 2);
        assertEquals(Ctx.run(request, () -> memExecutor.execute(next).toList()),
                Ctx.run(request, () -> pgExecutor.execute(next).toList()));
    }

    /**
     * 一致性坐标终于被兑现：撤销 doc:readme 的父指向之后，历史坐标仍然能读到当时的授权。
     *
     * <p>只记写入版本的实现会在这里失败——快照读会看到"当时已撤销"的世界。
     */
    @Test
    void snapshotReadSeesTheWorldAtThatRevision() {
        var link = Tuple.of(doc("spec"), PARENT, folder("eng"));
        pgTuples.revoke(new Revision(5), link);

        var checker = new Checker(SCHEMA, pgTuples, pgAttrs);
        var alice = principal("alice");

        assertTrue(Ctx.run(Ctx.Request.of(alice).at(new Revision(3)),
                () -> checker.check(doc("spec"), VIEW)).allowed(), "坐标 3 时链接还在");
        assertFalse(Ctx.run(Ctx.Request.of(alice).at(new Revision(9)),
                () -> checker.check(doc("spec"), VIEW)).allowed(), "坐标 9 时链接已撤销");
        assertFalse(Ctx.run(Ctx.Request.of(alice),
                () -> checker.check(doc("spec"), VIEW)).allowed(), "HEAD 也读不到");

        // 复原，避免影响其他用例（JUnit 默认类内顺序不保证，因此这里立刻回滚）
        pgTuples.write(new Revision(10), link);
    }

    /**
     * 通配主体在表里的编码能原样读回来。
     *
     * <p>{@code subject_id = ''} 这个哨兵值省掉了一列和一次迁移，代价是写入与读取必须在
     * 同一处转换上对齐。这条断言钉的就是那次往返：错一边的症状是 {@code user:*} 被读成
     * {@code Principal(user, "")}——一个构造器本来就拒绝的形状，而它会一路飘到判定里。
     */
    @Test
    void wildcardRoundTripsThroughTheTable() {
        pgTuples.write(OPENED, PUBLIC_VIEW);
        try {
            var found = Ctx.run(Ctx.Request.of(principal("alice")),
                    () -> pgTuples.subjects(doc("public"), VIEWER));

            assertEquals(List.of(new SubjectRef.Wildcard(USER)), List.copyOf(found));
        } finally {
            pgTuples.revoke(CLOSED, PUBLIC_VIEW);
        }
    }

    /**
     * 通配授权在真实 SQL 上对 check 与反查同时生效。
     *
     * <p>反查那一侧是这条用例的重点：它靠主体闭包里多播的一行
     * {@code SELECT ?::text, '', ''} 起作用，而那行只有在真正的递归 CTE 里执行过才算被验证。
     * 少了它的症状最难查——check 说"你能看"，列表接口里这份文档却根本不出现。
     */
    @Test
    void wildcardGrantIsVisibleToBothCheckAndReverseLookup() {
        pgTuples.write(OPENED, PUBLIC_VIEW);
        try {
            // newcomer 在库里一条元组都没有：公开资源不该要求先为他写入什么
            var request = Ctx.Request.of(principal("newcomer"));

            assertTrue(Ctx.run(request,
                    () -> new Checker(SCHEMA, pgTuples, pgAttrs).check(doc("public"), VIEW)).allowed());

            var plan = new Planner(SCHEMA, pgTuples.caps()).plan(DOC, VIEW, Cursor.START, 10);
            assertEquals(List.of(doc("public")),
                    Ctx.run(request, () -> pgExecutor.execute(plan).toList()));
        } finally {
            pgTuples.revoke(CLOSED, PUBLIC_VIEW);
        }
    }
}

package facet.store.pg;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Plan;
import facet.core.ir.Revision;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.FOLDER;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 千万级规模测量。
 *
 * <p>存在的唯一目的是回答一个悬着的架构决策：<strong>要不要把 Rel/ObjectType 编码成 int</strong>。
 * 原始设计说"在千万级元组上 String 有实打实的开销"，但十万级的测量显示瓶颈在 SQL 而不在
 * 对象头。判据是 {@code EXPLAIN ANALYZE} 报的数据库执行时间与端到端墙钟时间之差——
 * 那个差值才是 JDBC 传输加 Java 侧物化的成本，也只有它能被符号表优化掉。
 *
 * <p>数据用 {@code generate_series} 在服务端生成，不走 JDBC：一千万行的网络传输会让测量
 * 变成对驱动的测量。索引在灌完之后才建，否则插入期间的索引维护会占掉大部分时间。
 *
 * <p>跑一次要一两分钟，默认不进构建：{@code mvn test -Dfacet.scale.large=true}。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "facet.scale.large", matches = "true",
        disabledReason = "千万级灌数据要一两分钟，默认不进常规构建")
class PgLargeScaleTest {

    /** folder 链深度。 */
    private static final int DEPTH = 100;
    /** 每层 folder 下挂的 doc 数。DEPTH × 这个数 = 主体可见的 doc 总量。 */
    private static final int DOCS_PER_FOLDER = 1_000;
    /** 与判定无关的噪声行，用来把表撑到千万级。 */
    private static final int NOISE = 10_000_000;
    /** check 采样次数。 */
    private static final int SAMPLES = 200;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static PgTupleSource tuples;
    private static PgAttrSource attrs;
    private static PgPlanExecutor executor;

    @BeforeAll
    static void load() throws SQLException {
        Connections connections = () -> DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        tuples = new PgTupleSource(connections, 1 << 20);
        attrs = new PgAttrSource(connections);
        executor = new PgPlanExecutor(connections);

        long started = System.nanoTime();
        try (var conn = connections.get(); var st = conn.createStatement()) {
            // 只建表，索引留到灌完再建
            st.execute(PgSchema.ddl().getFirst());
            st.execute("CREATE SEQUENCE IF NOT EXISTS facet_revision AS bigint START 1");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS facet_attr (
                      object_type text NOT NULL, object_id text NOT NULL,
                      name text NOT NULL, value text NOT NULL,
                      PRIMARY KEY (object_type, object_id, name))""");

            // 深链：f1.parent = f0，f2.parent = f1 ……
            st.executeUpdate("""
                    INSERT INTO facet_tuple
                      (object_type, object_id, relation, subject_type, subject_id, subject_rel, rev_from)
                    SELECT 'folder', 'f' || g, 'parent', 'folder', 'f' || (g - 1), '', 1
                      FROM generate_series(1, %d) g""".formatted(DEPTH));
            // 每层挂一批 doc
            st.executeUpdate("""
                    INSERT INTO facet_tuple
                      (object_type, object_id, relation, subject_type, subject_id, subject_rel, rev_from)
                    SELECT 'doc', 'd' || f || '-' || d, 'parent', 'folder', 'f' || f, '', 1
                      FROM generate_series(0, %d) f, generate_series(1, %d) d"""
                    .formatted(DEPTH, DOCS_PER_FOLDER));
            // 噪声：与 alice 无关的直接授权
            st.executeUpdate("""
                    INSERT INTO facet_tuple
                      (object_type, object_id, relation, subject_type, subject_id, subject_rel, rev_from)
                    SELECT 'doc', 'n' || g, 'viewer', 'user', 'u' || g, '', 1
                      FROM generate_series(1, %d) g""".formatted(NOISE));

            for (var ddl : PgSchema.ddl().subList(1, 3)) {
                st.execute(ddl);
            }
            st.execute("ANALYZE facet_tuple");
        }
        tuples.write(new Revision(1), Tuple.of(folder(0), VIEWER, user("alice")));

        long rows;
        try (var conn = connections.get();
             var ps = conn.prepareStatement("SELECT count(*) FROM facet_tuple");
             var rs = ps.executeQuery()) {
            rs.next();
            rows = rs.getLong(1);
        }
        System.out.printf("装载 %,d 行，耗时 %.1f s%n", rows, seconds(started));
    }

    /** 规模上去之后索引仍然被用上——这是"反查可下推"这个主张在千万级上的复核。 */
    @Test
    void indexesStillHoldAtTenMillion() {
        var plan = planner().plan(DOC, VIEW, Cursor.START, 50);

        var explain = Ctx.run(Ctx.Request.of(principal("alice")), () -> executor.explainAnalyze(plan));

        System.out.println(explain);
        assertFalse(explain.contains("Seq Scan on facet_tuple"),
                "千万级下反查退化成全表扫:\n" + explain);
    }

    /**
     * 符号表的判据。
     *
     * <p>数据库执行时间来自 {@code EXPLAIN ANALYZE}，端到端时间是墙钟。两者之差是 JDBC 传输
     * 加 Java 侧物化——符号表只能优化掉这一段。若它在总耗时里占比很小，把 Rel/ObjectType
     * 编码成 int 就是没有测量支撑的优化。
     */
    @Test
    void reportsWhereTheTimeGoes() {
        var plan = planner().plan(DOC, VIEW, Cursor.START, 500);
        var request = Ctx.Request.of(principal("alice"));

        // 预热：首次要编译 SQL、建连接、填缓冲池
        Ctx.run(request, () -> executor.execute(plan).toList());

        long started = System.nanoTime();
        var page = Ctx.run(request, () -> executor.execute(plan).toList());
        double endToEnd = millis(started);

        var explain = Ctx.run(request, () -> executor.explainAnalyze(plan));
        double inDatabase = parseExecutionTime(explain);
        double outside = endToEnd - inDatabase;

        System.out.printf("""
                反查一页 %d 条：端到端 %.2f ms，其中数据库 %.2f ms，
                JDBC 传输 + Java 物化 %.2f ms（占 %.1f%%）%n""",
                page.size(), endToEnd, inDatabase, outside, 100 * outside / endToEnd);

        assertTrue(page.size() > 0, "应当反查到结果");
        assertTrue(inDatabase > 0, "EXPLAIN 里没解析到执行时间:\n" + explain);
    }

    /** check 路径：深链上逐层求值的实际代价。 */
    @Test
    void reportsCheckLatencyAtDepth() {
        var checker = new Checker(SCHEMA, tuples, attrs);
        var deepest = new ObjectRef(DOC, "d" + DEPTH + "-1");
        var request = Ctx.Request.of(principal("alice")).withMaxDepth(DEPTH * 4 + 8);

        assertTrue(Ctx.run(request, () -> checker.check(deepest, VIEW)).allowed(),
                "深链最底层应当放行");

        long started = System.nanoTime();
        for (int i = 0; i < SAMPLES; i++) {
            // 每次新建 Request：Memo 是请求内的，复用会把第二次之后全部变成缓存命中
            Ctx.run(Ctx.Request.of(principal("alice")).withMaxDepth(DEPTH * 4 + 8),
                    () -> checker.check(deepest, VIEW));
        }
        System.out.printf("深度 %d 的 check：%d 次平均 %.2f ms%n",
                DEPTH, SAMPLES, millis(started) / SAMPLES);
    }

    /** 分页翻到底：确认闭包在千万级下仍然覆盖整条链。 */
    @Test
    void paginationCoversTheWholeChain() {
        var planner = planner();
        var request = Ctx.Request.of(principal("alice"));
        var seen = new ArrayList<ObjectRef>();
        var cursor = Cursor.START;

        long started = System.nanoTime();
        while (true) {
            var current = cursor;
            var batch = Ctx.run(request,
                    () -> executor.execute(planner.plan(DOC, VIEW, current, 5_000)).toList());
            if (batch.isEmpty()) {
                break;
            }
            seen.addAll(batch);
            cursor = Cursor.of(batch.getLast());
        }
        System.out.printf("翻完 %,d 条可见 doc，耗时 %.1f s%n", seen.size(), seconds(started));

        assertTrue(seen.size() >= DEPTH * DOCS_PER_FOLDER,
                "闭包漏了：期望至少 " + DEPTH * DOCS_PER_FOLDER + "，实际 " + seen.size());
    }

    private static Planner planner() {
        return new Planner(SCHEMA, tuples.caps());
    }

    /** 从 {@code EXPLAIN ANALYZE} 的尾部取数据库执行时间（毫秒）。 */
    private static double parseExecutionTime(String explain) {
        return explain.lines()
                .filter(line -> line.startsWith("Execution Time:"))
                .mapToDouble(line -> Double.parseDouble(line.replaceAll("[^0-9.]", "")))
                .findFirst()
                .orElse(-1);
    }

    private static double millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private static double seconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    private static ObjectRef folder(int level) {
        return new ObjectRef(FOLDER, "f" + level);
    }

    private static ObjectRef user(String id) {
        return new ObjectRef(new ObjectType("user"), id);
    }
}

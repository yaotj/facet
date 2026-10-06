package facet.store.pg;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.Explains;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Revision;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
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
 * 规模验证：递归反查在深层级 + 十万级元组上到底走不走索引。
 *
 * <p>这是"递归 CTE 可下推"之后剩下的那个最贵的假设。它的答案不能靠推理，只能让数据库
 * 自己说——所以断言的是 {@code EXPLAIN ANALYZE} 的<strong>形状</strong>（有没有 Seq Scan），
 * 而不是某个毫秒数：时间在不同机器上没有可比性，"是否退化成全表扫"到哪都一样。
 *
 * <p>灌数据要几秒，所以默认不跑：{@code mvn test -Dfacet.scale=true}。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "facet.scale", matches = "true",
        disabledReason = "灌十万级元组要几秒，默认不进常规构建")
class PgScaleTest {

    /** folder 链的深度。远超默认深度上限，正好用来暴露那条限制。 */
    private static final int DEPTH = 60;
    /** 每层挂多少个 doc。 */
    private static final int DOCS_PER_FOLDER = 20;
    /** 与判定无关的噪声元组，用来把表撑大，逼优化器在没有索引时选全表扫。 */
    private static final int NOISE = 100_000;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static PgTupleSource tuples;
    private static PgAttrSource attrs;
    private static PgPlanExecutor executor;

    @BeforeAll
    static void load() {
        Connections connections = () -> DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        tuples = new PgTupleSource(connections, 1 << 20);
        tuples.migrate();
        attrs = new PgAttrSource(connections);
        executor = new PgPlanExecutor(connections);

        var batch = new ArrayList<Tuple>();
        // 一条深链：folder:1 的 parent 是 folder:0，folder:2 的 parent 是 folder:1……
        batch.add(Tuple.of(folder(0), VIEWER, user("alice")));
        for (int level = 1; level <= DEPTH; level++) {
            batch.add(Tuple.of(folder(level), PARENT, folder(level - 1)));
            for (int n = 0; n < DOCS_PER_FOLDER; n++) {
                batch.add(Tuple.of(doc(level + "-" + n), PARENT, folder(level)));
            }
        }
        // 噪声：一堆与 alice 无关的直接授权
        for (int n = 0; n < NOISE; n++) {
            batch.add(Tuple.of(doc("noise-" + n), VIEWER, user("u" + n)));
        }
        tuples.write(new Revision(1), batch);
        analyze();
    }

    /** 不 ANALYZE 的话优化器还在用空表的统计信息，EXPLAIN 出来的形状没有意义。 */
    private static void analyze() {
        try (var conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var st = conn.createStatement()) {
            st.execute("ANALYZE facet_tuple");
        } catch (Exception e) {
            throw new IllegalStateException("ANALYZE 失败", e);
        }
    }

    @Test
    void recursiveLookupStaysOnIndexes() {
        var plan = new Planner(SCHEMA, tuples.caps()).plan(DOC, VIEW, Cursor.START, 50);

        var explain = Ctx.run(Ctx.Request.of(principal("alice")), () -> executor.explainAnalyze(plan));

        System.out.println(explain);
        assertFalse(explain.contains("Seq Scan on facet_tuple"),
                "反查退化成全表扫，索引没被用上:\n" + explain);
    }

    /** 深层级下反查仍然只发一条语句，并且真的能翻到最深处的 doc。 */
    @Test
    void recursiveLookupReachesTheDeepestDocument() {
        var planner = new Planner(SCHEMA, tuples.caps());
        var deepest = doc(DEPTH + "-0");

        var found = new ArrayList<ObjectRef>();
        var cursor = Cursor.START;
        // 一页一页翻到底，确认闭包覆盖了整条链
        for (int page = 0; page < DEPTH * 2 && found.size() < DEPTH * DOCS_PER_FOLDER; page++) {
            var current = cursor;
            var batch = Ctx.run(Ctx.Request.of(principal("alice")),
                    () -> executor.execute(planner.plan(DOC, VIEW, current, 500)).toList());
            if (batch.isEmpty()) {
                break;
            }
            found.addAll(batch);
            cursor = Cursor.of(batch.getLast());
        }

        assertTrue(found.contains(deepest), "最深处的 doc 没被反查到，实际拿到 " + found.size() + " 条");
    }

    /**
     * 深度上限的单位是<strong>求值节点</strong>，不是层级。
     *
     * <p>默认 32 只够走七八层 folder；再深就是 DEPTH-EXCEEDED，而 explain 里只有一行提示。
     * 这条断言把"必须显式抬高"这件事钉住，免得有人在深目录部署上踩。
     */
    @Test
    void deepHierarchyNeedsRaisedDepthLimit() {
        var checker = new Checker(SCHEMA, tuples, attrs);
        var deepest = doc(DEPTH + "-0");

        var withDefault = Ctx.run(Ctx.Request.of(principal("alice")),
                () -> checker.check(deepest, VIEW));
        assertFalse(withDefault.allowed(), "默认深度上限本应挡住 " + DEPTH + " 层");
        assertTrue(Explains.render(withDefault.explain()).contains("DEPTH-EXCEEDED"),
                Explains.render(withDefault.explain()));

        var raised = Ctx.run(Ctx.Request.of(principal("alice")).withMaxDepth(DEPTH * 4 + 8),
                () -> checker.check(deepest, VIEW));
        assertTrue(raised.allowed(), "抬高上限后应当放行");
    }

    private static ObjectRef folder(int level) {
        return new ObjectRef(FOLDER, "f" + level);
    }

    private static ObjectRef doc(String id) {
        return new ObjectRef(DOC, id);
    }

    private static ObjectRef user(String id) {
        return new ObjectRef(new ObjectType("user"), id);
    }
}

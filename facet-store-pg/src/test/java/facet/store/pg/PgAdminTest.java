package facet.store.pg;

import facet.core.eval.Ctx;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleChange;
import facet.core.ir.TupleFilter;
import facet.core.spi.HistoryTruncatedException;
import facet.store.memory.MemoryTupleSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static facet.testkit.FolderScenario.BANNED;
import static facet.testkit.FolderScenario.EDITOR;
import static facet.testkit.FolderScenario.MEMBER;
import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.USER;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.group;
import static facet.testkit.FolderScenario.principal;
import static facet.testkit.FolderScenario.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维侧的按条件读取与批量撤销。
 *
 * <p>这两个方法不在求值端口上，因此没有判定矩阵替它们兜着：PG 侧的 {@code WHERE} 是手写的，
 * 它<strong>必须</strong>与 {@code TupleFilter.matches} 等价，排序也必须与内存适配器同一个序。
 * 一旦分歧，症状是"同一个游标在两个存储上翻到不同页"——导出会静默丢行，而每一页看着都正常。
 *
 * <p>撤销这一侧要钉住的是闭区间语义：{@code revokeWhere} 关区间而不是 DELETE，
 * 所以历史坐标仍然读得到那些元组。改成删除的话这里全绿、而快照读会在生产上突然少掉一段历史。
 *
 * <p>变更流也在这里：它读的是同一张表的 {@code rev_from} / {@code rev_to}，所以正确性同样落在
 * 手写 SQL 上。要钉住的是两件事——撤销那一支不能漏（漏了客户端只看到"又多了谁"），
 * 以及回收之后必须明确拒绝过早的起点而不是给一份缺了撤销的清单。
 *
 * <p>没有 Docker 时整类跳过：真实存储的验证不该成为构建的硬前提。
 */
@Testcontainers(disabledWithoutDocker = true)
class PgAdminTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Revision WRITTEN = new Revision(1);

    /**
     * 公开资源策略：{@code doc:public#viewer@user:*}。表里的 {@code subject_id} 是空串。
     *
     * <p>不放进 {@code FolderScenario.TUPLES}：那份 fixture 被多处按精确条数断言，
     * 而这两条元组只服务于通配筛选的精确性。清理靠 {@code @BeforeEach} 的 TRUNCATE。
     */
    private static final Tuple WILDCARD_GRANT =
            new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));

    /** 同一个对象、同一个关系上的具体授权——通配撤销不该碰它。 */
    private static final Tuple CONCRETE_GRANT = Tuple.of(doc("public"), VIEWER, user("dave"));

    private static Connections connections;
    private static PgTupleSource pgTuples;

    @BeforeAll
    static void setUp() {
        // 直接用 DriverManager 而不是 PGSimpleDataSource：Connections 只要一条连接，
        // 引入 DataSource 会顺带把 javax.naming 拖进模块图。
        connections = () -> DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        pgTuples = new PgTupleSource(connections);
        pgTuples.migrate();
        // 空跑一次 apply 把序列的第一个值用掉。元组写在坐标 1 上，而撤销的坐标由序列分配：
        // 两者相等时闭区间 [1,1) 是空的，"历史坐标仍可读"这条断言就会因为坐标碰撞而失败，
        // 看起来像是闭区间没生效。
        pgTuples.apply(List.of(), List.of());
    }

    @BeforeEach
    void reload() {
        truncate();
        pgTuples.write(WRITTEN, TUPLES);
    }

    /** PG 的 WHERE 必须与 {@code TupleFilter.matches} 等价，排序也必须与内存适配器同一个序。 */
    @Test
    void readAgreesWithTheMemoryAdapter() {
        var memTuples = new MemoryTupleSource().write(TUPLES);
        var request = Ctx.Request.of(principal("alice"));

        for (var filter : List.of(TupleFilter.ANY,
                TupleFilter.onObject(doc("readme")),
                TupleFilter.ofSubject(principal("alice")))) {
            var memory = Ctx.run(request, () -> memTuples.read(filter, null, 1000));
            var postgres = Ctx.run(request, () -> pgTuples.read(filter, null, 1000));
            assertEquals(memory, postgres, filter.toString());
        }

        // 顺序本身也钉一条：六列升序、字节序，所以 banned < editor < parent
        assertEquals(List.of(
                        Tuple.of(doc("readme"), BANNED, user("alice")),
                        Tuple.of(doc("readme"), EDITOR, user("alice")),
                        Tuple.of(doc("readme"), PARENT, folder("eng"))),
                read(TupleFilter.onObject(doc("readme")), null, 1000));
    }

    /** 游标就是上一页最后一条元组：一页一页翻下去必须不重不漏地覆盖整个集合。 */
    @Test
    void cursorPaginationWalksTheWholeSetOnPostgres() {
        var single = read(TupleFilter.ANY, null, 1000);

        var paged = new ArrayList<Tuple>();
        Tuple after = null;
        while (true) {
            var page = read(TupleFilter.ANY, after, 3);
            if (page.isEmpty()) {
                break;
            }
            paged.addAll(page);
            after = page.getLast();
        }

        assertEquals(single, paged);
        assertEquals(TUPLES.size(), paged.size());
    }

    /** 撤销是闭区间而不是 DELETE：HEAD 上读不到了，历史坐标仍然读得到。 */
    @Test
    void revokeWhereClosesIntervalsWithoutDeleting() {
        int before = rowCount();

        var revoked = pgTuples.revokeWhere(TupleFilter.onObject(doc("readme")));

        assertTrue(revoked.value() > WRITTEN.value(),
                "撤销坐标必须大于写入坐标，否则闭区间是空的: " + revoked.value());
        assertEquals(List.of(), read(TupleFilter.onObject(doc("readme")), null, 1000));
        assertEquals(3, readAt(WRITTEN, TupleFilter.onObject(doc("readme"))).size(),
                "坐标 " + WRITTEN.value() + " 上这三条元组当时还有效");
        assertEquals(before, rowCount(), "撤销不该删行，空间要靠 compact 回收");
    }

    /** 按主体撤销只清掉直接授权：经 group 拿到权限的人不受影响。 */
    @Test
    void revokeWhereBySubjectRemovesDirectGrants() {
        pgTuples.revokeWhere(TupleFilter.ofSubject(principal("alice")));

        assertEquals(List.of(), read(TupleFilter.ofSubject(principal("alice")), null, 1000));
        assertEquals(List.of(Tuple.of(group("eng"), MEMBER, user("carol"))),
                read(TupleFilter.ofSubject(principal("carol")), null, 1000));
    }

    /** 撤销与写入共用序列，所以坐标严格递增——调用方能拿它做写后一致读。 */
    @Test
    void revokeWhereReturnsAMonotonicRevision() {
        var first = pgTuples.revokeWhere(TupleFilter.onObject(doc("readme")));
        var second = pgTuples.revokeWhere(TupleFilter.onObject(doc("spec")));

        assertTrue(second.value() > first.value(),
                first.value() + " → " + second.value());
    }

    /**
     * 通配筛选在 PG 上同样必须精确。
     *
     * <p>这一条钉的是 {@code appendFilter} 里那处 {@code "*"} → {@code ''} 的翻译：
     * 筛选层用 {@code "*"} 做通配标记，表里的编码却是空串。不翻译，SQL 会去找
     * {@code subject_id = '*'} 而一行也匹配不到（假阴性，看起来像"这条公开策略不存在"）；
     * 翻译错成"不带这个条件"，就退化成"主体类型是 user 的全部元组"。
     */
    @Test
    void wildcardFilterIsPreciseOnPostgres() {
        pgTuples.write(WRITTEN, WILDCARD_GRANT, CONCRETE_GRANT);

        assertEquals(List.of(WILDCARD_GRANT), read(TupleFilter.wildcardsOf(USER), null, 100));
    }

    /**
     * 按通配撤销只关掉通配那一条，具体主体的授权留在 HEAD 上。
     *
     * <p>这是那个 bug 真正会造成损失的地方：{@code POST /v1/relationships/delete} 带上
     * "下线 user 的通配授权"这一个条件，如果它实际匹配的是"主体类型是 user 的全部元组"，
     * 一次按文档的调用就把该类型下所有人的授权全撤了，而且 {@code unconstrained()} 是
     * {@code false}，日志里没有任何警告。
     * <p>存活情况用裸 SQL 数，不经 {@code read}：撤销与读走的是同一个 {@code appendFilter}，
     * 用同一条筛选去验证会在"两边一起坏掉"时自证清白（撤销没匹配到、读也没匹配到，断言照样绿）。
     */
    @Test
    void revokeWhereOnWildcardsSparesConcreteGrants() {
        pgTuples.write(WRITTEN, WILDCARD_GRANT, CONCRETE_GRANT);

        pgTuples.revokeWhere(TupleFilter.wildcardsOf(USER));

        assertEquals(0, openRowsWithSubjectId(""), "通配授权应当已在 HEAD 上失效");
        assertEquals(1, openRowsWithSubjectId("dave"), "具体主体的授权不该被通配撤销带走");
        assertEquals(List.of(CONCRETE_GRANT),
                read(TupleFilter.ofSubject(principal("dave")), null, 100));
        // 场景数据里的其它直接授权同样不受影响
        assertEquals(List.of(Tuple.of(group("eng"), MEMBER, user("carol"))),
                read(TupleFilter.ofSubject(principal("carol")), null, 100));
        // 撤销仍然是闭区间：历史坐标上那条通配授权还在
        assertEquals(List.of(WILDCARD_GRANT, CONCRETE_GRANT),
                readAt(WRITTEN, TupleFilter.onObject(doc("public"))));
    }

    /**
     * 变更流要如实报出写入与撤销，包括各自生效的坐标。
     *
     * <p>撤销那一条是重点：它在表里不是一行新纪录，而是同一行的 {@code rev_to} 被改掉。
     * 变更流少了这一支的话，客户端只会看到"又多了谁"，永远看不到"谁被收回了"——
     * 而缓存据此更新的方向恰好是越来越松。
     */
    @Test
    void changesReportsCreationsAndRevocations() {
        var grant = Tuple.of(doc("public"), VIEWER, user("dave"));
        var other = Tuple.of(doc("public"), VIEWER, user("erin"));
        // 起点取当前水位：坐标由全局序列分配，不能在测试里写死
        var from = pgTuples.head();

        var granted = pgTuples.apply(List.of(grant, other), List.of());
        var revoked = pgTuples.apply(List.of(), List.of(grant));

        var page = pgTuples.changes(from, Revision.HEAD, 100);

        assertEquals(List.of(
                        new TupleChange(grant, true, granted),
                        new TupleChange(other, true, granted),
                        new TupleChange(grant, false, revoked)),
                page.changes());
        assertTrue(page.complete(), "已经追到 HEAD，不该要求客户端立刻再拉一次");
    }

    /**
     * 回收之后，一个落后太多的起点必须被明确拒绝。
     *
     * <p>这是变更流<strong>正确性</strong>的那一条：{@code compact} 真的删掉了已关闭的行，
     * 而那些行是"某条授权在某个坐标被撤销了"的唯一记录。此时若照常返回，客户端拿到的是一份
     * 缺了撤销的清单，它会把已经收回的权限继续留在缓存里放行——没有任何报错。
     */
    @Test
    void changesRejectsAStartBeforeTheWatermark() {
        var revoked = pgTuples.revokeWhere(TupleFilter.onObject(doc("readme")));

        pgTuples.compact(revoked);

        var failure = assertThrows(HistoryTruncatedException.class,
                () -> pgTuples.changes(new Revision(0), Revision.HEAD, 100));
        assertEquals(revoked.value(), failure.earliest().value(),
                "水位必须是回收的那个坐标，客户端据它判断落后了多少");
    }

    /**
     * 一个坐标不能被切成两批。
     *
     * <p>一次 {@code apply} 是原子的，半个坐标的变更是一份"改了一半"的集合——缓存照它更新会
     * 短暂地既不是旧状态也不是新状态。所以 {@code limit} 装不下单个坐标时，{@code page(...)}
     * 选择直接拒绝并要求调大 limit，而不是悄悄切开：后者只在大批量写入时才出现，
     * 症状是权限短暂错乱，事后完全无从追查。
     */
    @Test
    void changesNeverSplitsARevision() {
        var from = pgTuples.head();
        pgTuples.apply(List.of(
                Tuple.of(doc("public"), VIEWER, user("dave")),
                Tuple.of(doc("public"), VIEWER, user("erin")),
                Tuple.of(doc("public"), VIEWER, user("frank"))), List.of());

        var failure = assertThrows(IllegalArgumentException.class,
                () -> pgTuples.changes(from, Revision.HEAD, 2));

        assertTrue(failure.getMessage().contains("调大 limit"), failure.getMessage());
    }

    /** 每个 read 都要在 Ctx 里跑：适配器从 {@code Ctx.current().at()} 取时效坐标。 */
    private static List<Tuple> read(TupleFilter filter, Tuple after, int limit) {
        return Ctx.run(Ctx.Request.of(principal("alice")),
                () -> pgTuples.read(filter, after, limit));
    }

    private static List<Tuple> readAt(Revision at, TupleFilter filter) {
        return Ctx.run(Ctx.Request.of(principal("alice")).at(at),
                () -> pgTuples.read(filter, null, 1000));
    }

    private static int rowCount() {
        try (var conn = connections.get();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM facet_tuple")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("统计行数失败", e);
        }
    }

    /** HEAD 上某个 {@code subject_id} 的存活行数。通配在表里的编码是空串。 */
    private static int openRowsWithSubjectId(String subjectId) {
        var sql = "SELECT count(*) FROM facet_tuple WHERE subject_id = ? AND rev_to = ?";
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, subjectId);
            ps.setLong(2, PgSchema.OPEN);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计存活行失败", e);
        }
    }

    /**
     * 清库。
     *
     * <p>回收水位要一起清回 0：它不在 {@code facet_tuple} 里，只 TRUNCATE 元组表的话，
     * 某个用例调过 {@code compact} 之后水位就永久抬高了，而变更流那几条用例会随执行顺序
     * 时绿时红——那种失败最难认，因为单独跑每一条都是绿的。
     */
    private static void truncate() {
        try (var conn = connections.get(); var st = conn.createStatement()) {
            st.execute("TRUNCATE facet_tuple");
            st.execute("UPDATE facet_watermark SET value = 0");
        } catch (SQLException e) {
            throw new IllegalStateException("清库失败", e);
        }
    }
}

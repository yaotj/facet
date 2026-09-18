package facet.store.pg;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.spi.TupleSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PostgreSQL 元组存储。
 *
 * <p>唯一声明 {@code snapshotRead} 的适配器：元组不是被删除而是被闭区间，
 * {@code rev_from <= at < rev_to} 让"读某个坐标下的世界"成为一条普通的 WHERE。
 * 只记写入版本的实现会在快照读里看到当时已删除的元组，那种一致性是假的。
 *
 * <p>写入不在 {@code TupleSource} 端口上——端口是只读的。写路径各适配器自己暴露，
 * 因为一致性策略（批量、事务边界、版本分配）本身就是存储决策，统一抽象反而会把它抹平。
 *
 * <p><strong>连接来源必须是有界池。</strong>并行扇出下每个算子步骤各取一条连接，
 * 递归还会逐层叠加，{@code Caps.maxFanout} 应当与池容量相称，否则一次 check
 * 就能把池抽干。{@link Connections} 刻意不含池化语义，这个约束由装配方保证。
 *
 * <p><strong>多租户建议用 schema-per-tenant。</strong>让 {@link Connections} 交出的连接带上
 * 各自的 {@code search_path} 即可，表名不变、SQL 不变、IR 不变。加一列 {@code tenant} 的方案
 * 要改动每一条查询与每一个索引，换来的隔离性还更弱——一次漏加 WHERE 就是跨租户泄漏。
 * 唯一需要按租户区分的是写锁：{@code namespace} 决定顾问锁的键，不同租户因此不会互相串行。
 */
public final class PgTupleSource implements TupleSource {

    /** 默认命名空间。多租户部署应当每个租户给一个不同的值，避免写入互相串行。 */
    public static final String DEFAULT_NAMESPACE = "facet";

    /** 单批回收的行数上限。一条 DELETE 删完全部历史会形成超长事务并长时间持锁。 */
    private static final int COMPACT_BATCH = 10_000;

    private final Connections connections;
    private final int maxFanout;
    private final long writerLock;

    /**
     * @param namespace 写锁的命名空间。多租户部署每个租户给不同值，写入就不会跨租户串行
     */
    public PgTupleSource(Connections connections, int maxFanout, String namespace) {
        this.connections = connections;
        this.maxFanout = maxFanout;
        this.writerLock = namespace.hashCode() & 0xFFFF_FFFFL;
    }

    /** 命名空间取 {@link #DEFAULT_NAMESPACE}：单租户部署下所有写入共用同一把顾问锁。 */
    public PgTupleSource(Connections connections, int maxFanout) {
        this(connections, maxFanout, DEFAULT_NAMESPACE);
    }

    /** 扇出上限取 1024。这个值必须与连接池容量相称，池较小的部署应当显式传一个更小的值。 */
    public PgTupleSource(Connections connections) {
        this(connections, 1024, DEFAULT_NAMESPACE);
    }

    /** 建表建索引。幂等，可以在每次启动时无条件调用。 */
    public void migrate() {
        try (var conn = connections.get(); var st = conn.createStatement()) {
            for (var ddl : PgSchema.ddl()) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new PgException("建表失败", e);
        }
    }

    /**
     * 原子提交一批变更，并<strong>由存储分配</strong>一致性坐标。
     *
     * <p>这是唯一安全的写入口，三件事必须在同一个事务里：
     * <ul>
     *   <li><strong>整批原子。</strong>逐条自动提交的话，读者会看到"角色已撤销但新角色还没加上"
     *       的中间状态——授权变更恰恰最不能有中间态。</li>
     *   <li><strong>坐标在事务内分配。</strong>否则两个并发写各拿到 R1 &lt; R2 却按 R2、R1 的
     *       顺序提交，坐标 R2 的读者能看到 R2 却看不到 R1——这就是 Zanzibar 说的 new enemy
     *       problem，表现为"刚撤销的人还能看到刚共享给他的东西"。</li>
     *   <li><strong>事务级顾问锁。</strong>把坐标分配顺序钉成提交顺序。代价是写入被串行化；
     *       授权写入的量级通常远低于读，这个代价换的是可推理的一致性，值得。真要提高写吞吐，
     *       应该做的是按租户分锁，而不是去掉锁。</li>
     * </ul>
     *
     * @return 本批变更生效的坐标
     */
    public Revision apply(Collection<Tuple> writes, Collection<Tuple> revokes) {
        var assigned = new long[1];
        inTransaction(conn -> {
            lockWriters(conn, writerLock);
            assigned[0] = nextRevision(conn);
            revokeAll(conn, assigned[0], revokes);
            writeAll(conn, assigned[0], writes);
        });
        return new Revision(assigned[0]);
    }

    /** 当前坐标水位。反查与 check 需要"读我刚写的"时，把它放进 {@code Ctx.Request.at}。 */
    public Revision head() {
        try (var conn = connections.get();
             var ps = conn.prepareStatement("SELECT last_value FROM facet_revision");
             var rs = ps.executeQuery()) {
            return rs.next() ? new Revision(rs.getLong(1)) : new Revision(0);
        } catch (SQLException e) {
            throw new PgException("读取坐标水位失败", e);
        }
    }

    /**
     * 回收水位之前的历史行。
     *
     * <p>{@code revoke} 只闭区间不删除，所以历史会无界增长。清理必须按水位而不是按时间：
     * 只要还有客户端可能拿着旧坐标来读，那个区间的数据就不能删。水位应当取
     * "最老的仍在使用的坐标"，通常是 PDP 的缓存有效期换算出来的值。
     *
     * <p>分批删除并逐批提交：历史积压大时一条 DELETE 会形成超长事务、膨胀 WAL 并长时间持锁。
     *
     * @return 删除的行数
     */
    public int compact(Revision watermark) {
        if (watermark.isHead()) {
            throw new IllegalArgumentException("回收水位必须是具体坐标：HEAD 会把全部历史删掉");
        }
        var sql = """
                DELETE FROM facet_tuple
                 WHERE ctid IN (SELECT ctid FROM facet_tuple WHERE rev_to <= ? LIMIT ?)""";
        int total = 0;
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            while (true) {
                ps.setLong(1, watermark.value());
                ps.setInt(2, COMPACT_BATCH);
                int deleted = ps.executeUpdate();
                total += deleted;
                if (deleted < COMPACT_BATCH) {
                    return total;
                }
            }
        } catch (SQLException e) {
            throw new PgException("回收历史元组失败（已删除 " + total + " 行）", e);
        }
    }

    /** 写入指定坐标。仅供导入/回填：并发使用时没有 {@link #apply} 的顺序保证。 */
    public void write(Revision at, Tuple... tuples) {
        write(at, List.of(tuples));
    }

    /** 写入指定坐标。仅供导入/回填。 */
    public void write(Revision at, Collection<Tuple> tuples) {
        requireConcrete(at);
        inTransaction(conn -> writeAll(conn, at.value(), tuples));
    }

    /** 撤销：闭区间而不是 DELETE，历史坐标仍然可读。 */
    public void revoke(Revision at, Tuple... tuples) {
        requireConcrete(at);
        inTransaction(conn -> revokeAll(conn, at.value(), List.of(tuples)));
    }

    @Override
    public Set<SubjectRef> subjects(ObjectRef obj, Rel rel) {
        // ORDER BY 与 SubjectRef.ORDER 对齐；COLLATE "C" 对齐 Keys 的 UTF-8 字节序。
        // 少了任何一半，explain 的分支顺序就会和内存适配器分叉。
        // LIMIT 把扇出上限下推到数据库：Checker 里的校验发生在数据加载完之后，
        // 热点对象上百万条元组会先把 PDP 内存打满再报错。
        var sql = """
                SELECT subject_type, subject_id, subject_rel
                  FROM facet_tuple
                 WHERE object_type = ? AND object_id = ? AND relation = ?
                   AND rev_from <= ? AND ? < rev_to
                 ORDER BY subject_type COLLATE "C", subject_id COLLATE "C", subject_rel COLLATE "C"
                 LIMIT ?""";
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            long at = Rows.at();
            ps.setString(1, obj.type().name());
            ps.setString(2, obj.id());
            ps.setString(3, rel.name());
            ps.setLong(4, at);
            ps.setLong(5, at);
            ps.setInt(6, maxFanout + 1);
            var out = new LinkedHashSet<SubjectRef>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Rows.toSubject(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
            requireWithinFanout(out.size(), obj.type().name() + ':' + obj.id() + '#' + rel.name());
            return Collections.unmodifiableSet(out);
        } catch (SQLException e) {
            throw new PgException("读取 subjects 失败", e);
        }
    }

    @Override
    public Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type) {
        var sql = """
                SELECT object_id FROM facet_tuple
                 WHERE subject_type = ? AND subject_id = ? AND subject_rel = ?
                   AND relation = ? AND object_type = ?
                   AND rev_from <= ? AND ? < rev_to
                 ORDER BY object_id COLLATE "C"
                 LIMIT ?""";
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            var s = Rows.of(subject);
            long at = Rows.at();
            ps.setString(1, s.type());
            ps.setString(2, s.id());
            ps.setString(3, s.rel());
            ps.setString(4, rel.name());
            ps.setString(5, type.name());
            ps.setLong(6, at);
            ps.setLong(7, at);
            ps.setInt(8, maxFanout + 1);
            var out = new ArrayList<ObjectRef>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ObjectRef(type, rs.getString(1)));
                }
            }
            requireWithinFanout(out.size(), s.type() + ':' + s.id() + " → " + rel.name());
            return out.stream();
        } catch (SQLException e) {
            throw new PgException("读取 objects 失败", e);
        }
    }

    /** 三项能力全部支持；{@code snapshotRead} 来自 {@code rev_from}/{@code rev_to} 时效区间。 */
    @Override
    public Caps caps() {
        return new Caps(true, true, true, maxFanout);
    }

    private void requireWithinFanout(int loaded, String where) {
        if (loaded > maxFanout) {
            throw new PgException(where + " 的扇出超过上限 " + maxFanout
                    + "，拒绝继续加载。热点对象需要先拆分关系，而不是抬高上限。", null);
        }
    }

    private static void requireConcrete(Revision at) {
        if (at.isHead()) {
            throw new IllegalArgumentException("写入必须给出具体坐标，不能用 Revision.HEAD");
        }
    }

    /**
     * 事务边界。
     *
     * <p>{@code RuntimeException} 必须和 {@code SQLException} 一样触发回滚：JDBC 规定切换
     * {@code autoCommit} 会提交当前事务，漏掉这一支就意味着一个 NPE 能把半批授权变更提交落库。
     *
     * <p>回滚与恢复自身的异常用 {@code addSuppressed} 挂到原异常上，不替换它——否则事故现场
     * 只剩下连接层错误，真实原因丢失。
     */
    private void inTransaction(SqlAction action) {
        try (var conn = connections.get()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                action.run(conn);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn, e);
                throw e;
            } finally {
                restoreQuietly(conn, autoCommit);
            }
        } catch (SQLException e) {
            throw new PgException("提交授权变更失败", e);
        }
    }

    private static void rollbackQuietly(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (SQLException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }

    private static void restoreQuietly(Connection conn, boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // 连接即将被关闭，恢复失败不影响正确性；不能在这里抛，否则会顶掉原始异常
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection conn) throws SQLException;
    }

    /** 顾问锁只活到事务结束，进程崩溃不会留下悬挂锁——这是不用表锁的理由。 */
    private static void lockWriters(Connection conn, long key) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, key);
            ps.executeQuery().close();
        }
    }

    private static long nextRevision(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT nextval('facet_revision')");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void writeAll(Connection conn, long revision, Collection<Tuple> tuples)
            throws SQLException {
        if (tuples.isEmpty()) {
            return;
        }
        var sql = """
                INSERT INTO facet_tuple
                  (object_type, object_id, relation, subject_type, subject_id, subject_rel, rev_from)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (var ps = conn.prepareStatement(sql)) {
            for (var tuple : tuples) {
                var subject = Rows.of(tuple.subject());
                ps.setString(1, tuple.object().type().name());
                ps.setString(2, tuple.object().id());
                ps.setString(3, tuple.relation().name());
                ps.setString(4, subject.type());
                ps.setString(5, subject.id());
                ps.setString(6, subject.rel());
                ps.setLong(7, revision);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void revokeAll(Connection conn, long revision, Collection<Tuple> tuples)
            throws SQLException {
        if (tuples.isEmpty()) {
            return;
        }
        var sql = """
                UPDATE facet_tuple SET rev_to = ?
                 WHERE object_type = ? AND object_id = ? AND relation = ?
                   AND subject_type = ? AND subject_id = ? AND subject_rel = ?
                   AND rev_to = ?""";
        try (var ps = conn.prepareStatement(sql)) {
            for (var tuple : tuples) {
                var subject = Rows.of(tuple.subject());
                ps.setLong(1, revision);
                ps.setString(2, tuple.object().type().name());
                ps.setString(3, tuple.object().id());
                ps.setString(4, tuple.relation().name());
                ps.setString(5, subject.type());
                ps.setString(6, subject.id());
                ps.setString(7, subject.rel());
                ps.setLong(8, PgSchema.OPEN);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

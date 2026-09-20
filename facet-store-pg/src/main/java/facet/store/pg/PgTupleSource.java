package facet.store.pg;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleChange;
import facet.core.ir.TupleFilter;
import facet.core.spi.HistoryTruncatedException;
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

    private static final System.Logger LOG = System.getLogger(PgTupleSource.class.getName());

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

    /**
     * 建表建索引。幂等，可以在每次启动时无条件调用。
     *
     * <p>整批放在一个事务里：Postgres 的 DDL 是事务性的，中途失败不该留下"建了一半"的表结构，
     * 那种半成品要靠人去分辨哪几条已经执行过。也因此不能依赖连接的 {@code autoCommit} 默认值。
     */
    public void migrate() {
        inTransaction(conn -> {
            try (var st = Statements.of(conn, connections)) {
                for (var ddl : PgSchema.ddl()) {
                    st.execute(ddl);
                }
            }
        }, "建表失败");
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
        }, "提交授权变更失败");
        return new Revision(assigned[0]);
    }

    /** 当前坐标水位。反查与 check 需要"读我刚写的"时，把它放进 {@code Ctx.Request.at}。 */
    public Revision head() {
        try (var conn = connections.get();
             var ps = Statements.of(conn, "SELECT last_value FROM facet_revision", connections);
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
     * 事务边界在这里是<strong>显式</strong>的，不能沿用连接的 {@code autoCommit} 默认值——
     * 连接池普遍配成 {@code autoCommit=false}，那样"逐批提交"会变成一个大事务，
     * 而且在连接归还时整批回滚，症状是"回收报告删了几十万行，表却一行没少"。
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
        try (var conn = connections.get()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 水位<strong>先</strong>抬、再删：中途失败时水位声明的历史比实际拥有的少，
                // 变更流会拒绝一个本来还能服务的客户端。反过来写才是危险的——那会让它给出
                // 一份缺了撤销的清单，而客户端据此更新缓存的结果是已收回的权限继续放行
                advanceWatermark(conn, watermark.value());
                conn.commit();
                try (var ps = Statements.of(conn, sql, connections)) {
                    while (true) {
                        ps.setLong(1, watermark.value());
                        ps.setInt(2, COMPACT_BATCH);
                        int deleted = ps.executeUpdate();
                        conn.commit();
                        total += deleted;
                        if (deleted < COMPACT_BATCH) {
                            return total;
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn, e);
                throw e;
            } finally {
                restoreQuietly(conn, autoCommit);
            }
        } catch (SQLException e) {
            throw new PgException("回收历史元组失败（已提交删除 " + total + " 行）", e);
        }
    }

    /** 水位只增不减：并发的两次回收里较小的那个不该把它拉回去。 */
    private void advanceWatermark(Connection conn, long value) throws SQLException {
        try (var ps = Statements.of(conn,
                "UPDATE facet_watermark SET value = GREATEST(value, ?)", connections)) {
            ps.setLong(1, value);
            ps.executeUpdate();
        }
    }

    // ---- 变更流 ----

    /**
     * 拉取 {@code (from, to]} 区间内的元组变更。
     *
     * <p>用途是让客户端缓存做<strong>精确失效</strong>。只靠 TTL 的话，授权变更到生效之间必然
     * 有一个窗口，而那个窗口里被收回的权限仍然放行。
     *
     * <p>存储层已经具备条件：撤销是闭区间不删行，所以"某条授权在哪个坐标被撤销"就记在
     * {@code rev_to} 上。变更流因此只是对同一张表的另一种读法，不需要额外的日志表。
     *
     * <p><strong>批次边界永远落在坐标上。</strong>一次 {@code apply} 是原子的，把它切成两批
     * 会让缓存看到一份改了一半的集合。{@code limit} 因此是软上限：实际返回的行数会退到最后一个
     * 完整坐标的边界。若<em>单个</em>坐标的变更就超过 {@code limit}，这里直接拒绝而不是切开它——
     * 悄悄切开是那种只在大批量写入时才出现、且症状是权限短暂错乱的问题。
     *
     * @param from  起点，开区间下界。必须不早于 {@link #watermark()}
     * @param to    终点，闭区间上界；传 {@code HEAD} 表示追到当前
     * @param limit 单批行数软上限
     * @throws HistoryTruncatedException {@code from} 早于回收水位，这一段的撤销记录已被删除
     */
    public TupleChange.Page changes(Revision from, Revision to, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("单批上限必须为正");
        }
        requireConcrete(from);
        var earliest = watermark();
        if (from.value() < earliest.value()) {
            throw new HistoryTruncatedException(from, earliest);
        }
        long upper = to.isHead() ? PgSchema.OPEN - 1 : to.value();
        if (upper <= from.value()) {
            return new TupleChange.Page(List.of(), from, true);
        }
        // 两支 UNION ALL：写入看 rev_from，撤销看 rev_to。rev_to = OPEN 的行是"仍然有效"，
        // 不是一次撤销，必须排除掉。
        // UNION 必须包进子查询再排序：Postgres 对 UNION 的 ORDER BY 只接受输出列名，
        // 而这里的排序键带 COLLATE——直接写在外层会是一条语法错误的 SQL
        var sql = """
                SELECT object_type, object_id, relation, subject_type, subject_id, subject_rel,
                       at, created
                  FROM (SELECT object_type, object_id, relation,
                               subject_type, subject_id, subject_rel,
                               rev_from AS at, true AS created
                          FROM facet_tuple
                         WHERE rev_from > ? AND rev_from <= ?
                        UNION ALL
                        SELECT object_type, object_id, relation,
                               subject_type, subject_id, subject_rel,
                               rev_to AS at, false AS created
                          FROM facet_tuple
                         WHERE rev_to > ? AND rev_to <= ? AND rev_to <> ?) AS changes
                 ORDER BY at, %s, created
                 LIMIT ?""".formatted(orderBy());
        try (var conn = connections.get(); var ps = Statements.of(conn, sql, connections)) {
            ps.setLong(1, from.value());
            ps.setLong(2, upper);
            ps.setLong(3, from.value());
            ps.setLong(4, upper);
            ps.setLong(5, PgSchema.OPEN);
            // 多取一行，用来判断"是否还有"以及切在哪个坐标边界上
            ps.setInt(6, limit + 1);
            var rows = new ArrayList<TupleChange>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TupleChange(
                            new Tuple(new ObjectRef(new ObjectType(rs.getString(1)), rs.getString(2)),
                                    new Rel(rs.getString(3)),
                                    Rows.toSubject(rs.getString(4), rs.getString(5), rs.getString(6))),
                            rs.getBoolean(8),
                            new Revision(rs.getLong(7))));
                }
            }
            return page(rows, new Revision(upper), limit);
        } catch (SQLException e) {
            throw new PgException("拉取变更流失败", e);
        }
    }

    /** 把多取的一行去掉，并把批次切在最后一个完整坐标上。 */
    private static TupleChange.Page page(List<TupleChange> rows, Revision upper, int limit) {
        if (rows.size() <= limit) {
            return new TupleChange.Page(rows, upper, true);
        }
        long spill = rows.get(limit).at().value();
        var kept = rows.stream().filter(change -> change.at().value() < spill).toList();
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("坐标 " + spill + " 单独的变更数就超过 "
                    + limit + "：拉变更流不能把一个坐标切成两批，请调大 limit");
        }
        return new TupleChange.Page(kept, kept.getLast().at(), false);
    }

    /**
     * 变更流最早还能覆盖到的坐标。
     *
     * <p>{@link #compact} 会真正删掉已关闭的行，而那些行是撤销记录的唯一载体。这个水位就是
     * "从这里之前的撤销我已经答不上来了"，{@link #changes} 据它拒绝过早的起点。
     */
    public Revision watermark() {
        try (var conn = connections.get();
             var ps = Statements.of(conn, "SELECT value FROM facet_watermark", connections);
             var rs = ps.executeQuery()) {
            return rs.next() ? new Revision(rs.getLong(1)) : new Revision(0);
        } catch (SQLException e) {
            throw new PgException("读取回收水位失败", e);
        }
    }

    /** 写入指定坐标。仅供导入/回填：并发使用时没有 {@link #apply} 的顺序保证。 */
    public void write(Revision at, Tuple... tuples) {
        write(at, List.of(tuples));
    }

    // ---- 运维侧：按条件读取与批量撤销 ----

    /** 运维读取时的列顺序，同时也是排序键与游标的比较顺序。 */
    private static final List<String> KEY_COLUMNS = List.of(
            "object_type", "object_id", "relation", "subject_type", "subject_id", "subject_rel");

    /**
     * 按条件读回元组。
     *
     * <p>{@code TupleSource} 上那三个读方法都是求值形状的，答不出"这个对象上到底写了什么"。
     * 迁移、审计导出、离职前的影响面确认都需要这一个，而它<strong>不属于</strong>求值端口：
     * 把它加到 {@code TupleSource} 上会让每个适配器都得实现一个和判定无关的方法。
     *
     * <p>排序按六列升序、{@code COLLATE "C"} 对齐字节序，与内存适配器一致。现有索引没有带
     * 这个排序规则，所以这条查询会走排序——它是运维路径，不在 check 的热路径上，
     * 用确定的跨适配器顺序换掉索引是划算的。
     *
     * @param after 上一页最后一条元组；首页传 {@code null}。用元组本身而不是编码过的
     *              游标串：六个字段里任何一个都可能含分隔符，编码就得处理转义
     * @param limit 单页条数上限
     */
    public List<Tuple> read(TupleFilter filter, Tuple after, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("单页上限必须为正");
        }
        var values = new ArrayList<Object>();
        var sql = new StringBuilder("""
                SELECT object_type, object_id, relation, subject_type, subject_id, subject_rel
                  FROM facet_tuple
                 WHERE rev_from <= ? AND ? < rev_to""");
        long at = Rows.at();
        values.add(at);
        values.add(at);
        appendFilter(sql, values, filter);
        if (after != null) {
            appendAfter(sql, values, after);
        }
        sql.append("\n ORDER BY ").append(orderBy()).append("\n LIMIT ?");
        values.add(limit);

        try (var conn = connections.get(); var ps = Statements.of(conn, sql.toString(), connections)) {
            bind(ps, values);
            var out = new ArrayList<Tuple>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tuple(
                            new ObjectRef(new ObjectType(rs.getString(1)), rs.getString(2)),
                            new Rel(rs.getString(3)),
                            Rows.toSubject(rs.getString(4), rs.getString(5), rs.getString(6))));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new PgException("按条件读取元组失败", e);
        }
    }

    /**
     * 按条件批量撤销。
     *
     * <p>逐条 {@link #revoke} 要求调用方先把元组完整枚举出来，而"删除一个租户"、
     * "清理一个已删除的资源"、"离职清理"都拿不到那份清单。
     *
     * <p>和 {@link #apply} 走同一条路径：事务级顾问锁 + 序列分配坐标。撤销同样是闭区间
     * 而不是 DELETE，所以历史坐标上的读仍然看得到这些元组——这也意味着它<strong>不释放空间</strong>，
     * 空间要靠 {@link #compact} 回收。
     *
     * <p>{@link TupleFilter#ANY} 是合法输入（清空整个库是真实需求），但会落一条 WARNING 日志：
     * 一次无约束的撤销如果是某个字段忘填的意外，日志是唯一能事后看出来的痕迹。
     *
     * @return 本次撤销生效的坐标
     */
    public Revision revokeWhere(TupleFilter filter) {
        if (filter.unconstrained()) {
            LOG.log(System.Logger.Level.WARNING,
                    "收到无约束的批量撤销：将关闭全部仍然有效的元组");
        }
        var assigned = new long[1];
        inTransaction(conn -> {
            lockWriters(conn, writerLock);
            assigned[0] = nextRevision(conn);
            var values = new ArrayList<Object>();
            var sql = new StringBuilder("UPDATE facet_tuple SET rev_to = ?\n WHERE rev_to = ?");
            values.add(assigned[0]);
            values.add(PgSchema.OPEN);
            appendFilter(sql, values, filter);
            try (var ps = Statements.of(conn, sql.toString(), connections)) {
                bind(ps, values);
                ps.executeUpdate();
            }
        }, "按条件撤销元组失败");
        return new Revision(assigned[0]);
    }

    /** 把筛选条件编成 WHERE 片段。{@code null} 字段不产生条件，因此天然就是"任意"。 */
    private static void appendFilter(StringBuilder sql, List<Object> values, TupleFilter filter) {
        appendEquals(sql, values, "object_type",
                filter.objectType() == null ? null : filter.objectType().name());
        appendEquals(sql, values, "object_id", filter.objectId());
        appendEquals(sql, values, "relation",
                filter.relation() == null ? null : filter.relation().name());
        appendEquals(sql, values, "subject_type",
                filter.subjectType() == null ? null : filter.subjectType().name());
        // 筛选层用 "*" 表示通配，表里的编码是空串——翻译只在这一处发生
        appendEquals(sql, values, "subject_id",
                SubjectRef.WILDCARD_ID.equals(filter.subjectId()) ? "" : filter.subjectId());
        // subjectRel 为 null 是"任意"；要匹配具体主体应当传一个空 Rel 名做不到，
        // 所以这里的语义是：给了 rel 就只匹配 userset，不给就两者都匹配
        appendEquals(sql, values, "subject_rel",
                filter.subjectRel() == null ? null : filter.subjectRel().name());
    }

    private static void appendEquals(StringBuilder sql, List<Object> values,
                                     String column, String value) {
        if (value != null) {
            sql.append("\n   AND ").append(column).append(" = ?");
            values.add(value);
        }
    }

    /**
     * 游标条件：按六列的字典序取"大于上一条"的部分。
     *
     * <p>写成显式的 OR 链而不是行比较 {@code (a,b,...) > (...)}：行比较用的是默认排序规则，
     * 而 ORDER BY 用的是 {@code COLLATE "C"}，两者不一致会让某一页被跳过或重复。
     * 分页的正确性要求比较与排序<strong>必须是同一个序</strong>。
     */
    private static void appendAfter(StringBuilder sql, List<Object> values, Tuple after) {
        var key = keyOf(after);
        sql.append("\n   AND (");
        for (int level = 0; level < KEY_COLUMNS.size(); level++) {
            if (level > 0) {
                sql.append("\n     OR ");
            }
            sql.append('(');
            for (int prefix = 0; prefix < level; prefix++) {
                sql.append(KEY_COLUMNS.get(prefix)).append(" COLLATE \"C\" = ? AND ");
                values.add(key.get(prefix));
            }
            sql.append(KEY_COLUMNS.get(level)).append(" COLLATE \"C\" > ?)");
            values.add(key.get(level));
        }
        sql.append(')');
    }

    private static List<String> keyOf(Tuple tuple) {
        var subject = Rows.of(tuple.subject());
        return List.of(tuple.object().type().name(), tuple.object().id(), tuple.relation().name(),
                subject.type(), subject.id(), subject.rel());
    }

    private static String orderBy() {
        return KEY_COLUMNS.stream().map(column -> column + " COLLATE \"C\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void bind(java.sql.PreparedStatement ps, List<Object> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setObject(i + 1, values.get(i));
        }
    }

    /** 写入指定坐标。仅供导入/回填。 */
    public void write(Revision at, Collection<Tuple> tuples) {
        requireConcrete(at);
        inTransaction(conn -> writeAll(conn, at.value(), tuples), "导入元组失败");
    }

    /** 撤销：闭区间而不是 DELETE，历史坐标仍然可读。 */
    public void revoke(Revision at, Tuple... tuples) {
        requireConcrete(at);
        inTransaction(conn -> revokeAll(conn, at.value(), List.of(tuples)), "撤销元组失败");
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
        try (var conn = connections.get(); var ps = Statements.of(conn, sql, connections)) {
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
        try (var conn = connections.get(); var ps = Statements.of(conn, sql, connections)) {
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
     *
     * @param whatFailed 包进 {@link PgException} 的说明；每个写入口不同，好让日志能直接定位
     */
    private void inTransaction(SqlAction action, String whatFailed) {
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
            throw new PgException(whatFailed, e);
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

    /**
     * 顾问锁只活到事务结束，进程崩溃不会留下悬挂锁——这是不用表锁的理由。
     *
     * <p>它同样带语句超时：写入被这把锁串行化，一个卡住的写者不设上限就会把同命名空间的
     * 全部写入无限期堵住，而调用方看到的只是"提交没有返回"。
     */
    private void lockWriters(Connection conn, long key) throws SQLException {
        try (var ps = Statements.of(conn, "SELECT pg_advisory_xact_lock(?)", connections)) {
            ps.setLong(1, key);
            ps.executeQuery().close();
        }
    }

    private long nextRevision(Connection conn) throws SQLException {
        try (var ps = Statements.of(conn, "SELECT nextval('facet_revision')", connections);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void writeAll(Connection conn, long revision, Collection<Tuple> tuples)
            throws SQLException {
        if (tuples.isEmpty()) {
            return;
        }
        var sql = """
                INSERT INTO facet_tuple
                  (object_type, object_id, relation, subject_type, subject_id, subject_rel, rev_from)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (var ps = Statements.of(conn, sql, connections)) {
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

    private void revokeAll(Connection conn, long revision, Collection<Tuple> tuples)
            throws SQLException {
        if (tuples.isEmpty()) {
            return;
        }
        var sql = """
                UPDATE facet_tuple SET rev_to = ?
                 WHERE object_type = ? AND object_id = ? AND relation = ?
                   AND subject_type = ? AND subject_id = ? AND subject_rel = ?
                   AND rev_to = ?""";
        try (var ps = Statements.of(conn, sql, connections)) {
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

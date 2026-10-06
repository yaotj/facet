package facet.store.pg;

import facet.core.runtime.Ctx;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Plan;
import facet.core.spi.PlanExecutor;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 把整个 {@link Plan} 下推成一条 SQL 执行。
 *
 * <p>没有任何一步回到应用层做循环——这正是 {@code Plan} 这套 IR 的存在理由。
 *
 * <p>编译结果按<strong>计划形状</strong>缓存：分页游标与页大小走绑定参数，不进 SQL 文本，
 * 因此缓存键把它们归一掉。若按原始 {@code Plan} 缓存，客户端只要不断变换游标就能让缓存
 * 无界膨胀——一个只读接口就成了内存耗尽的入口。
 *
 * <p>缓存本身也是<strong>有界</strong>的。归一化让键空间等于 schema 的形状数，看起来够小，
 * 但那个前提在两处会破：热更新会引入新形状而旧形状永不淘汰（PDP 换 schema 时不重建执行器），
 * 手工拼计划的调用方也不受 schema 约束。容量上限是不依赖这些前提的兜底。
 */
public final class PgPlanExecutor implements PlanExecutor {

    /** 归一化后的分页参数。取任意合法值即可，它们不出现在 SQL 文本里。 */
    private static final int KEY_LIMIT = 1;

    /**
     * 编译缓存的容量上限。
     *
     * <p>取 256：归一化之后一个键对应一个"类型 × 关系"的反查形状，几百个已经远超任何
     * 手写 schema 的规模。真要撞上这个数，说明形状不是来自 schema 而是来自请求，
     * 那时该做的是查清来源，而不是把上限调大。
     */
    private static final int MAX_COMPILED = 256;

    private final Connections connections;
    private final Map<Plan, SqlQuery> compiled = boundedCache();

    /** @param connections 编译缓存挂在实例上，所以同一份装配应当长期复用同一个执行器 */
    public PgPlanExecutor(Connections connections) {
        this.connections = connections;
    }

    /** 访问序 LRU。并发下靠 synchronizedMap：编译是每形状一次的冷路径，不值得为它做无锁结构。 */
    private static Map<Plan, SqlQuery> boundedCache() {
        var lru = new LinkedHashMap<Plan, SqlQuery>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Plan, SqlQuery> eldest) {
                return size() > MAX_COMPILED;
            }
        };
        return Collections.synchronizedMap(lru);
    }

    /**
     * 供测试与排查用：看一眼某个计划会发出什么 SQL。
     *
     * <p>不用 {@code computeIfAbsent}：{@code synchronizedMap} 下它会在整张表的锁里跑编译，
     * 而编译是纯函数，重复做一次只是浪费一点 CPU，把锁按住才是真问题。
     */
    public SqlQuery explainSql(Plan plan) {
        var key = cacheKey(plan);
        var hit = compiled.get(key);
        if (hit != null) {
            return hit;
        }
        var query = PlanSqlCompiler.compile(key);
        compiled.put(key, query);
        return query;
    }

    /**
     * 让数据库解释这条计划怎么执行。
     *
     * <p>不只是测试工具：反查编出来的是一条相当复杂的语句，"它到底走没走索引"只有数据库
     * 能回答。做成 API，排查慢查询时就不必手工重拼参数。
     */
    public String explainAnalyze(Plan plan) {
        var query = explainSql(plan);
        var sql = "EXPLAIN (ANALYZE, BUFFERS) " + query.sql();
        try (var conn = connections.get(); var ps = Statements.of(conn, sql, connections)) {
            bind(ps, query.params(), plan);
            var out = new StringBuilder();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.append(rs.getString(1)).append('\n');
                }
            }
            return out.toString();
        } catch (SQLException e) {
            throw new PgException("EXPLAIN 失败:\n" + query.sql(), e);
        }
    }

    /** 一条 SQL 出全部结果，中途不回应用层循环；结果一次物化，Stream 不会带着已关闭的连接逃出去。 */
    @Override
    public Stream<ObjectRef> execute(Plan plan) {
        // 物化的前提是结果集有上限，而上限只能由 Page 给出
        PlanExecutor.requirePaged(plan);
        var query = explainSql(plan);
        try (var conn = connections.get(); var ps = Statements.of(conn, query.sql(), connections)) {
            bind(ps, query.params(), plan);
            var out = new ArrayList<ObjectRef>();
            // 类型名在结果集里高度重复（一次反查通常只有一两个类型），驻留掉能省下
            // 与行数同阶的 ObjectType 分配。这是符号表思路里唯一已被 EXPLAIN 证实
            // 落在热路径上的一段；把 Rel/ObjectType 全面编码成 int 目前没有测量支撑，
            // 10 万元组下瓶颈在 SQL 而不在对象头。
            var types = new HashMap<String, ObjectType>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var type = types.computeIfAbsent(rs.getString(1), ObjectType::new);
                    out.add(new ObjectRef(type, rs.getString(2)));
                }
            }
            // 一次物化：Stream 逃出 try-with-resources 会带着一个已关闭的连接。
            return out.stream();
        } catch (SQLException e) {
            throw new PgException("执行反查计划失败:\n" + query.sql(), e);
        }
    }

    /**
     * 当前缓存了多少种计划形状。
     *
     * <p>给可观测性用：这个数应当在启动后迅速稳定在"类型 × 关系"的量级上。它持续增长
     * 意味着形状来自请求而不是 schema，那是个应当去查清来源的信号。
     */
    public int compiledShapes() {
        return compiled.size();
    }

    /** 分页值不影响 SQL 文本，从缓存键里剔掉，避免游标成为缓存膨胀的入口。 */    private static Plan cacheKey(Plan plan) {
        return plan instanceof Plan.Page(var input, _, _)
                ? new Plan.Page(input, Cursor.START, KEY_LIMIT)
                : plan;
    }

    /** 没有 {@code default} 分支：{@code Param} 加一种标记，绑定这里立刻编译失败。 */
    private static void bind(PreparedStatement ps, List<Param> params, Plan plan) throws SQLException {
        var request = Ctx.current();
        var principal = Rows.of(request.principal());
        long at = Rows.at(request.at());
        var page = plan instanceof Plan.Page p ? p : null;

        int index = 1;
        for (var param : params) {
            switch (param) {
                case Param.Literal(var value) -> ps.setObject(index, value);
                case Param.PrincipalType _ -> ps.setString(index, principal.type());
                case Param.PrincipalId _ -> ps.setString(index, principal.id());
                case Param.PrincipalRel _ -> ps.setString(index, principal.rel());
                case Param.At _ -> ps.setLong(index, at);
                case Param.ContextAttr(var name) ->
                        ps.setString(index, Rows.text(request.contextAttrs().get(name)));
                case Param.After _ -> ps.setString(index, requirePage(page).after().token());
                case Param.Limit _ -> ps.setInt(index, requirePage(page).limit());
            }
            index++;
        }
    }

    private static Plan.Page requirePage(Plan.Page page) {
        if (page == null) {
            throw new IllegalStateException("计划里有分页占位符，但顶层不是 Plan.Page");
        }
        return page;
    }
}

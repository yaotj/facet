package facet.store.pg;

import facet.core.runtime.Ctx;
import facet.core.runtime.DeadlineExceededException;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 语句工厂。
 *
 * <p>存在的理由是让两件事<strong>没法漏设</strong>：
 * <ul>
 *   <li><strong>语句超时。</strong>直接调 {@code conn.prepareStatement} 拿到的语句没有执行上限，
 *       一条卡住的查询会一直占着连接与虚拟线程，而这种漏设在代码评审里基本看不出来——
 *       三个适配器一共十来个语句创建点，少一个就是一个不受控的挂起入口。</li>
 *   <li><strong>把请求期限传导到语句上。</strong>期限在求值节点之间检查只能保证"不再发起
 *       新的调用"；已经在途的那一次仍会跑满它自己的超时。一个 200ms 的期限如果配着 10 秒的
 *       语句超时，实际最坏返回时间是 10.2 秒——那个期限等于没设。</li>
 * </ul>
 */
final class Statements {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private Statements() {
    }

    /** 带超时的预编译语句。 */
    static PreparedStatement of(java.sql.Connection conn, String sql, Connections source)
            throws SQLException {
        return bounded(conn.prepareStatement(sql), source);
    }

    /** 带超时的普通语句，给 DDL 用。 */
    static Statement of(java.sql.Connection conn, Connections source) throws SQLException {
        return bounded(conn.createStatement(), source);
    }

    private static <S extends Statement> S bounded(S statement, Connections source)
            throws SQLException {
        int configured = source.queryTimeoutSeconds();
        if (configured < 0) {
            statement.close();
            throw new IllegalArgumentException("语句超时不能为负；不限执行时间请显式传 0");
        }
        try {
            // 0 是 JDBC 约定的"不限"，无期限时原样透传：显式选择不限和忘记设置应当长得不一样
            statement.setQueryTimeout(withDeadline(configured, statement));
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
        return statement;
    }

    /**
     * 用请求期限收紧超时。
     *
     * <p><strong>向上取整</strong>是必须的：JDBC 的 {@code setQueryTimeout} 单位是秒，
     * 而向下取整会把"还剩 300ms"变成 0，也就是"不限执行时间"——期限越紧反而越没有上限。
     */
    private static int withDeadline(int configured, Statement statement) throws SQLException {
        var deadline = Ctx.deadline();
        if (!deadline.bounded()) {
            return configured;
        }
        var left = deadline.remaining();
        if (left.isZero()) {
            statement.close();
            throw new DeadlineExceededException("请求期限已到，不再向存储发起新的查询");
        }
        long ceilSeconds = (left.toNanos() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
        int capped = (int) Math.min(ceilSeconds, Integer.MAX_VALUE);
        return configured == 0 ? capped : Math.min(configured, capped);
    }
}

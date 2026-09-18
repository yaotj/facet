package facet.store.pg;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 语句工厂。
 *
 * <p>存在的唯一理由是让超时<strong>没法漏设</strong>：直接调 {@code conn.prepareStatement}
 * 拿到的语句没有执行上限，一条卡住的查询会一直占着连接与虚拟线程，而这种漏设在代码评审里
 * 基本看不出来——三个适配器一共十来个语句创建点，少一个就是一个不受控的挂起入口。
 */
final class Statements {

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
        int seconds = source.queryTimeoutSeconds();
        if (seconds < 0) {
            statement.close();
            throw new IllegalArgumentException("语句超时不能为负；不限执行时间请显式传 0");
        }
        // 0 是 JDBC 约定的"不限"，原样透传：显式选择不限和忘记设置应当在代码里长得不一样
        statement.setQueryTimeout(seconds);
        return statement;
    }
}

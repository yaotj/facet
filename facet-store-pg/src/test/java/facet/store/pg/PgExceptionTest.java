package facet.store.pg;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障分类：这次失败还能不能再试一次。
 *
 * <p>不分类的后果是调用方只能在"全都重试"和"全都不重试"之间选，两个都错：前者会在数据库
 * 正在恢复时把压力再翻一倍，后者会把一次一秒的抖动变成一次用户可见的失败。
 *
 * <p>不需要数据库：分类只看 {@code SQLState} 与异常类型，那正是它该被单测钉住的原因——
 * 这些码在集成测试里恰好都很难制造出来。
 */
class PgExceptionTest {

    @Test
    void connectionFailureIsRetryable() {
        // 08006 connection_failure：连接断了，换一条重试即可
        assertTrue(retryable("08006"));
        assertTrue(retryable("08003"));
    }

    @Test
    void serializationFailureAndDeadlockAreRetryable() {
        assertTrue(retryable("40001"), "序列化冲突");
        assertTrue(retryable("40P01"), "死锁");
    }

    @Test
    void resourceExhaustionIsRetryable() {
        assertTrue(retryable("53300"), "连接数用满");
        assertTrue(retryable("53100"), "磁盘满");
    }

    /** 语句超时属于 57 类。它是可重试的：那一次太慢，不代表下一次也太慢。 */
    @Test
    void statementTimeoutIsRetryable() {
        assertTrue(retryable("57014"), "query_canceled");
        assertTrue(retryable("57P01"), "管理员关闭连接");
    }

    @Test
    void lockNotAvailableIsRetryable() {
        assertTrue(retryable("55P03"));
    }

    /** 约束违例、语法错、权限不足：重试一万次结果相同。 */
    @Test
    void permanentFailuresAreNotRetryable() {
        assertFalse(retryable("23505"), "唯一约束违例");
        assertFalse(retryable("42601"), "语法错误");
        assertFalse(retryable("42501"), "权限不足");
        assertFalse(retryable("22P02"), "类型转换失败");
    }

    /** 同类里的具体码会随版本增加，按类判断才不会漏；55 这一类只有 55P03 例外。 */
    @Test
    void otherCodesInClass55AreNotRetryable() {
        assertFalse(retryable("55006"), "对象正在使用");
    }

    /** 适配器自己的拒绝（扇出超限之类）没有 cause：那是形状问题，重试无意义。 */
    @Test
    void adapterRejectionIsNotRetryable() {
        assertFalse(new PgException("扇出超过上限", null).retryable());
    }

    /** 拿不到错误码时按不可重试处理：宁可少重试一次，也不要在未知故障上加压。 */
    @Test
    void missingSqlStateIsNotRetryable() {
        assertFalse(new PgException("失败", new SQLException("没有 SQLState")).retryable());
    }

    /** JDBC 自己的瞬时异常类型同样算：驱动已经做过判断，不该被 SQLState 的白名单否掉。 */
    @Test
    void jdbcTransientTypesAreRetryable() {
        assertTrue(new PgException("超时", new SQLTimeoutException("慢")).retryable());
        assertTrue(new PgException("连接", new SQLTransientConnectionException("断了")).retryable());
    }

    private static boolean retryable(String sqlState) {
        return new PgException("失败", new SQLException("模拟", sqlState)).retryable();
    }
}

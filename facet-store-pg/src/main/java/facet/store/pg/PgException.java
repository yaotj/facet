package facet.store.pg;

import facet.core.spi.StorageException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * JDBC 故障。把 {@code SQLException} 挡在端口之外——端口签名不该带受检异常。
 *
 * <p>同时把"能不能重试"从 {@code SQLState} 翻译成端口层面的一个布尔值，见
 * {@link StorageException}。翻译放在适配器里是因为只有它认得这些错误码。
 */
public class PgException extends StorageException {

    /**
     * 明确可重试的 {@code SQLState}。
     *
     * <p>取整类（前两位）而不是逐个列举，是因为同一类里的具体码会随 Postgres 版本增加，
     * 漏掉一个的后果是把一次可恢复的抖动报成永久失败：
     * <ul>
     *   <li>{@code 08} 连接异常——连接断了，换一条连接重试即可；</li>
     *   <li>{@code 40} 事务回滚——序列化冲突与死锁，重试是标准处置；</li>
     *   <li>{@code 53} 资源不足——连接数用满、磁盘满，稍后可能已缓解；</li>
     *   <li>{@code 57} 操作介入——正在重启或 failover，以及语句超时（{@code 57014}）。</li>
     * </ul>
     *
     * <p>不在其中的一律按不可重试处理：约束违例、语法错、权限不足重试一万次结果相同，
     * 把它们标成可重试只会让上层在故障时把压力翻倍。
     */
    private static final Set<String> RETRYABLE_CLASSES = Set.of("08", "40", "53", "57");

    /** 锁不可用。它的类是 {@code 55}（对象状态不对），但这一个具体码确实值得重试。 */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /**
     * @param cause 触发故障的 {@code SQLException}；不是 JDBC 引起的（如扇出超限）传 {@code null}
     */
    public PgException(String message, Throwable cause) {
        super(message, cause, retryable(cause));
    }

    private static boolean retryable(Throwable cause) {
        // cause 为 null 说明这是适配器自己的拒绝（扇出超限之类）：那是形状问题，重试无意义
        if (!(cause instanceof SQLException sql)) {
            return false;
        }
        if (sql instanceof SQLTransientException || sql instanceof SQLRecoverableException
                || sql instanceof SQLTimeoutException) {
            return true;
        }
        var state = sql.getSQLState();
        if (state == null || state.length() < 2) {
            // 拿不到错误码时按不可重试处理：宁可少重试一次，也不要在未知故障上加压
            return false;
        }
        return RETRYABLE_CLASSES.contains(state.substring(0, 2)) || LOCK_NOT_AVAILABLE.equals(state);
    }
}

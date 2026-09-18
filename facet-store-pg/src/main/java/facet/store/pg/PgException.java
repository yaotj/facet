package facet.store.pg;

/** JDBC 故障。把 {@code SQLException} 挡在端口之外——端口签名不该带受检异常。 */
public class PgException extends RuntimeException {

    /**
     * @param cause 触发故障的 {@code SQLException}；不是 JDBC 引起的（如扇出超限）传 {@code null}
     */
    public PgException(String message, Throwable cause) {
        super(message, cause);
    }
}

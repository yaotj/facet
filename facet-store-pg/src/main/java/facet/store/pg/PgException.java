package facet.store.pg;

/** JDBC 故障。把 {@code SQLException} 挡在端口之外——端口签名不该带受检异常。 */
public class PgException extends RuntimeException {

    public PgException(String message, Throwable cause) {
        super(message, cause);
    }
}

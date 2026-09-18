package facet.ir.json;

/** 编解码失败。线格式错误一律是调用方的问题，不该被当成服务端故障。 */
public class SchemaJsonException extends RuntimeException {

    public SchemaJsonException(String message) {
        super(message);
    }

    public SchemaJsonException(String message, Throwable cause) {
        super(message, cause);
    }
}

package facet.core.eval;

/** schema 加载期的拒绝。启动即失败，而不是上线后某个列表接口开始超时。 */
public class SchemaException extends RuntimeException {

    public SchemaException(String message) {
        super(message);
    }
}

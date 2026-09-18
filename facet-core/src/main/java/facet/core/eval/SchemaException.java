package facet.core.eval;

/** schema 加载期的拒绝。启动即失败，而不是上线后某个列表接口开始超时。 */
public class SchemaException extends RuntimeException {

    /**
     * 只有消息、没有 cause：加载期拒绝都是内核自己判定的，不存在底层异常。
     *
     * @param message 必须点明是哪个 {@code 类型#关系} 违反了哪条规则——它是启动失败时唯一的线索
     */
    public SchemaException(String message) {
        super(message);
    }
}

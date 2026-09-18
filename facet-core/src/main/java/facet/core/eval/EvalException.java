package facet.core.eval;

/** 求值期故障。包裹端口抛出的受检异常，不让它们污染六个算子分支的签名。 */
public class EvalException extends RuntimeException {

    /**
     * @param cause 端口抛出的原始异常。必须挂上：存储超时、连接耗尽、序列化失败在这一层看起来都一样，
     *              原始栈是唯一的区分依据
     */
    public EvalException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 内核自己判定的故障，例如扇出超过 {@code Caps.maxFanout}、存储未声明所需能力——没有更底层的 cause。 */
    public EvalException(String message) {
        super(message);
    }
}

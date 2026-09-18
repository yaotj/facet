package facet.core.eval;

/** 求值期故障。包裹端口抛出的受检异常，不让它们污染六个算子分支的签名。 */
public class EvalException extends RuntimeException {

    public EvalException(String message, Throwable cause) {
        super(message, cause);
    }

    public EvalException(String message) {
        super(message);
    }
}

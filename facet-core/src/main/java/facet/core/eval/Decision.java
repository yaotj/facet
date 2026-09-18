package facet.core.eval;

/** 判定结果：结论 + 判定树。两者一起返回，因为"为什么"和"是不是"同等重要。 */
public record Decision(boolean allowed, Explain explain) {

    public static Decision allow(Explain explain) {
        return new Decision(true, explain);
    }

    public static Decision deny(Explain explain) {
        return new Decision(false, explain);
    }
}

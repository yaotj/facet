package facet.core.runtime;

/** 判定结果：结论 + 判定树。两者一起返回，因为"为什么"和"是不是"同等重要。 */
public record Decision(boolean allowed, Explain explain) {

    /** 便捷构造，但仍然强制传入判定树：允许出现"没有理由的 allow"，越权排查就无从下手。 */
    public static Decision allow(Explain explain) {
        return new Decision(true, explain);
    }

    /** 同 {@link #allow}：deny 也必须带理由，"为什么没权限"恰恰是最常被追问的那一半。 */
    public static Decision deny(Explain explain) {
        return new Decision(false, explain);
    }
}

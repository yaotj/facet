package facet.core.ir;

/**
 * 属性键。
 *
 * <p>{@link Tier} 是整个内核最要紧的一个约束：它决定该属性允许出现在哪些判定路径上。
 * 不设这条线，一条 ABAC 规则就能静默毁掉关系图的可反查性——而且要等到某个列表接口
 * 开始超时才会被发现。
 */
public record AttrKey(String name, Tier tier) {

    public AttrKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("属性名不能为空");
        }
    }

    public enum Tier {

        /** 请求上下文自带，无 IO：时间、来源 IP、MFA 状态、client_id。 */
        CONTEXT,

        /** 随元组一起落库的资源快照属性，可建索引，可参与反查。 */
        SNAPSHOT,

        /**
         * 需要外部拉取（调 HR 系统问是否在职之类）。
         *
         * <p>有 IO、不可索引，因此<strong>禁止</strong>出现在可反查路径（反查资源、
         * 部分求值）。只允许作为 check 的最后一跳做后置过滤，并且要批量化。
         * 这条规则在 schema 加载期强制，不留到查询期。
         */
        EXTERNAL
    }
}

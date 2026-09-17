/**
 * ABAC 前端：属性策略 -&gt; {@link facet.core.ir.Perm.Guarded}。
 *
 * <p>这个前端最容易写出反查不了的 schema，因为它天生倾向于引用外部属性。所以它的
 * 编译产物必须能通过内核的可反查校验；把属性标成
 * {@link facet.core.ir.AttrKey.Tier#EXTERNAL} 是有代价的选择，不是默认。
 */
module facet.dsl.abac {
    requires facet.core;
}

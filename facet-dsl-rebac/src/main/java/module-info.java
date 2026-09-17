/**
 * ReBAC 前端：关系式 schema 文本 -&gt; {@link facet.core.ir.Perm}。
 *
 * <p>前端只有一个职责：<strong>编译</strong>。如果某天这里需要自己实现 check，
 * 说明 IR 表达力不够，该扩 IR 并承担 major 版本代价，而不是给前端开后门——那条路
 * 通向"每个模型一个 check 实现"，跨模型反查就再也做不到了。
 */
module facet.dsl.rebac {
    requires facet.core;
}

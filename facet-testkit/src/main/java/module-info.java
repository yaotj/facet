/**
 * 判定矩阵快照测试。
 *
 * <p>把"一组主体 × 一组资源"的完整判定表连同命中路径序列化成 golden file，策略改动时
 * diff 这张表。这是唯一能看出"这次改动意外放开了什么"的手段——权限回归没有其他可靠
 * 的防线，因为单个 check 的用例永远覆盖不到组合爆炸。
 */
module facet.testkit {
    requires facet.core;
}

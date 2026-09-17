/**
 * 内存元组存储：{@code TupleSource} 的参考实现。
 *
 * <p>它的价值不只是测试替身，而是<strong>端口语义的基准</strong>：反向索引、快照读、
 * 扇出上限三项能力全部支持，因此任何在这里通过、换到真实存储后失败的行为，都能立刻
 * 定位成适配器缺陷而非内核缺陷。
 */
module facet.store.memory {
    requires facet.core;
}

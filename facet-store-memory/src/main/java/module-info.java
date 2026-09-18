/**
 * 内存元组存储：{@code TupleSource} 与 {@code PlanExecutor} 的参考实现。
 *
 * <p>它的价值不只是测试替身，而是<strong>端口语义的基准</strong>：反向索引、扇出上限
 * 两项能力都支持，因此任何在这里通过、换到真实存储后失败的行为，都能立刻定位成适配器
 * 缺陷而非内核缺陷。快照读刻意不支持——{@code Caps.snapshotRead=false} 并且非 HEAD 的读
 * 直接抛错，好让"写后一致读"这个保证不会在换存储之前一直看起来是成立的。
 */
module facet.store.memory {
    requires facet.core;

    exports facet.store.memory;
}

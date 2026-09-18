/**
 * 结构化并发版扇出。
 *
 * <p>{@code AnyOf} 用"任一成功即取消其余分支"的语义，{@code Minus} 可以并行先算 denied
 * 分支以提前否决。这是 preview API 唯一被允许出现的模块——用它的代价是运行时也要带
 * {@code --enable-preview}，所以内核默认走顺序实现，这里是可选升级而非必需依赖。
 */
module facet.fanout.structured {
    requires facet.core;

    exports facet.fanout.structured;
}

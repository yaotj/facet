package facet.core.ir;

import java.util.List;

/**
 * 一次元组变更。变更流的元素。
 *
 * <p>为什么需要变更流：{@code DecisionCache} 目前只能靠 TTL 失效，所以授权变更到生效之间
 * 必然有一个窗口。缓存要做精确失效，就得知道"从我上次看到的坐标到现在，哪些元组变了"。
 *
 * <p>只有两种变更，因为存储层只有两种操作：写入与撤销。没有"修改"——元组是不可变的，
 * 改授权就是撤销一条再写一条，变更流里会是两个条目。
 *
 * @param tuple   变更涉及的元组
 * @param created {@code true} 是新增授权，{@code false} 是撤销
 * @param at      变更生效的坐标。客户端应当把本批最大的坐标记下来做下一次的起点
 */
public record TupleChange(Tuple tuple, boolean created, Revision at) {

    public TupleChange {
        if (tuple == null) {
            throw new IllegalArgumentException("变更必须带元组");
        }
        if (at == null || at.isHead()) {
            throw new IllegalArgumentException("变更坐标必须是具体值：HEAD 表达不了\"在哪一刻变的\"");
        }
    }

    /**
     * 一批变更。
     *
     * <p><strong>批次边界永远落在坐标上，不会切开一个坐标。</strong>一次 {@code apply} 是原子的，
     * 半个坐标的变更是一份"改了一半"的集合——缓存照它更新会短暂地既不是旧状态也不是新状态。
     * 所以这里不用行游标，而是用 {@link #nextFrom()} 直接给出下一次的起点。
     *
     * @param changes  本批变更，按坐标升序
     * @param nextFrom 下一次拉取的起点（开区间下界）。它一定是一个<em>完整覆盖</em>了的坐标
     * @param complete 是否已经追到请求的上界。{@code false} 表示还有，应当立刻再拉一次
     */
    public record Page(List<TupleChange> changes, Revision nextFrom, boolean complete) {

        public Page {
            changes = List.copyOf(changes);
            if (nextFrom == null || nextFrom.isHead()) {
                throw new IllegalArgumentException("下一次起点必须是具体坐标");
            }
        }
    }
}

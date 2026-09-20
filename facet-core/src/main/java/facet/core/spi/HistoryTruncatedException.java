package facet.core.spi;

import facet.core.ir.Revision;

/**
 * 请求的起始坐标已经被历史回收清掉了。
 *
 * <p>这个异常是变更流<strong>正确性</strong>的关键，不是一个边角情况。回收（{@code compact}）
 * 会真正删掉已关闭的行，那些行正是"某条授权在某个坐标被撤销了"的唯一记录。客户端如果从一个
 * 早于回收水位的坐标拉变更，它拿到的是一份<em>缺了撤销</em>的清单——而缓存据此更新的结果是
 * 已经被收回的权限继续放行。
 *
 * <p>所以适配器必须能回答"我还能覆盖到多早"，并在覆盖不到时明确拒绝，而不是返回一份不完整的
 * 清单。收到它的唯一正确处置是<strong>丢弃本地缓存、从当前 HEAD 重新开始</strong>。
 */
public final class HistoryTruncatedException extends RuntimeException {

    private final Revision requested;
    private final Revision earliest;

    public HistoryTruncatedException(Revision requested, Revision earliest) {
        super("请求的起始坐标 " + requested.value() + " 早于历史回收水位 " + earliest.value()
                + "：这一段的撤销记录已被删除，变更流无法保证完整。"
                + "正确处置是丢弃本地缓存，从当前 HEAD 重新开始");
        this.requested = requested;
        this.earliest = earliest;
    }

    /** 客户端请求的起点。 */
    public Revision requested() {
        return requested;
    }

    /** 变更流实际能覆盖到的最早坐标。客户端可以据此判断落后了多少。 */
    public Revision earliest() {
        return earliest;
    }
}

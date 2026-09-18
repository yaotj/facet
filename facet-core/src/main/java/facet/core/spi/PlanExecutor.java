package facet.core.spi;

import facet.core.ir.ObjectRef;
import facet.core.ir.Plan;

import java.util.stream.Stream;

/**
 * 计划执行端口：适配器把 {@link Plan} 下推到自己的查询能力上。
 *
 * <p>内核不提供通用实现——通用实现只能是"全部拉到内存再算集合"，那会让每个适配器都
 * 失去下推的机会。{@code facet.store.memory} 里那份是参考实现，不是共享基类。
 */
public interface PlanExecutor {

    /**
     * 执行一条计划。
     *
     * <p><strong>顶层必须是 {@link Plan.Page}</strong>，见 {@link #requirePaged}。
     */
    Stream<ObjectRef> execute(Plan plan);

    /**
     * 要求计划自带条数上限。
     *
     * <p>反查的结果集大小由数据决定，不由请求决定：一个 {@code doc#view} 可能对应三条，
     * 也可能对应三千万条。{@code Plan.Page} 是这个 IR 里唯一表达"最多要多少"的算子，
     * 顶层没有它，适配器就只能把整个结果集拉回来——而这一步在拉完之前没人知道有多大。
     *
     * <p>{@code Planner} 产出的计划一律带 {@code Page}，所以这道检查拦的是<strong>手工拼计划
     * 的调用方</strong>。放在端口上而不是各适配器自己判断：一个新适配器漏掉它，症状是
     * 在小数据集上一切正常、上量之后 OOM，而那时已经很难把原因追回到端口契约上。
     *
     * @return 顶层的 {@code Page}，便于适配器直接取 {@code after}/{@code limit}
     */
    static Plan.Page requirePaged(Plan plan) {
        if (plan instanceof Plan.Page page) {
            return page;
        }
        throw new IllegalArgumentException(
                "反查计划的顶层必须是 Plan.Page：结果集大小由数据决定，"
                        + "没有条数上限就没有任何东西能阻止一次查询把整张表拉进内存");
    }
}

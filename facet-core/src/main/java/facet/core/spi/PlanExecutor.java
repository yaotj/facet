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

    Stream<ObjectRef> execute(Plan plan);
}

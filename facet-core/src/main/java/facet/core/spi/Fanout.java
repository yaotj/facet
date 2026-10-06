package facet.core.spi;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import facet.core.spi.decorators.SequentialFanout;

/**
 * 扇出端口。
 *
 * <p>存在的唯一理由是把 preview API 关在外面：结构化并发在 JDK 25 仍是 preview，内核
 * 不能带 {@code --enable-preview}，否则整个依赖树都要跟着开。真正的并行实现放在
 * {@code facet.fanout.structured}——preview API 再换签名，只有那一个模块要改。
 */
public interface Fanout {

    /** 全部跑完。{@code AllOf} 与 {@code Minus} 用它——两边结果都要。 */
    <T> List<T> all(List<Callable<T>> tasks) throws Exception;

    /**
     * 命中即取消其余分支，返回<strong>已完成</strong>的结果（提交顺序，被取消的不在其中）。
     *
     * <p>不直接返回 {@code Optional<T>} 是为了保住 explain：判定树需要所有已完成分支的
     * 解释，只拿到命中那一支就没法解释"为什么另外几支没中"。
     */
    <T> List<T> firstMatch(List<Callable<T>> tasks, Predicate<T> hit) throws Exception;

    /** 默认实现：顺序执行，零依赖。 */
    Fanout SEQUENTIAL = new SequentialFanout();
}

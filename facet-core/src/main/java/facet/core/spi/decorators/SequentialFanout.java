package facet.core.spi.decorators;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import facet.core.spi.Fanout;

/**
 * {@link Fanout#SEQUENTIAL} 的实现。并行实现走独立模块。
 */
public final class SequentialFanout implements Fanout {

    /** 串行下不存在"部分完成"：任一分支抛出就直接冒泡，{@code AllOf} 绝不会拿到一份残缺的结果集。 */
    @Override
    public <T> List<T> all(List<Callable<T>> tasks) throws Exception {
        var out = new ArrayList<T>(tasks.size());
        for (var task : tasks) {
            out.add(task.call());
        }
        return List.copyOf(out);
    }

    /**
     * 串行下"取消其余分支"就是提前 return：命中之后的任务根本不会被调用。
     *
     * <p>命中优先于失败——已经有分支命中时，另一支的存储故障不改变结论。反过来，无一命中且存在
     * 失败时必须把失败抛出：那时的结论是"未知"，静默返回空结果会被上层读成 deny。
     */
    @Override
    public <T> List<T> firstMatch(List<Callable<T>> tasks, Predicate<T> hit) throws Exception {
        var out = new ArrayList<T>(tasks.size());
        Exception failure = null;
        for (var task : tasks) {
            T result;
            try {
                result = task.call();
            } catch (Exception e) {
                // 先记下来继续跑：某个分支的存储故障不代表其他分支不会命中，
                // 而命中是单调的——一旦命中，失败分支的答案无关紧要。
                if (failure == null) {
                    failure = e;
                }
                continue;
            }
            out.add(result);
            if (hit.test(result)) {
                return List.copyOf(out);
            }
        }
        if (failure != null) {
            // 没有任何分支命中，且有分支未能求值：结论是"未知"，绝不能当成 deny 返回
            throw failure;
        }
        return List.copyOf(out);
    }
}

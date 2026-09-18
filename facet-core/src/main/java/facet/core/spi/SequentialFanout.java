package facet.core.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * {@link Fanout#SEQUENTIAL} 的实现。故意不公开：并行实现走独立模块。
 */
final class SequentialFanout implements Fanout {

    @Override
    public <T> List<T> all(List<Callable<T>> tasks) throws Exception {
        var out = new ArrayList<T>(tasks.size());
        for (var task : tasks) {
            out.add(task.call());
        }
        return List.copyOf(out);
    }

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

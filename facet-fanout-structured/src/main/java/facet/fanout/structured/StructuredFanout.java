package facet.fanout.structured;

import facet.core.spi.Fanout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.function.Predicate;

/**
 * 结构化并发版扇出。
 *
 * <p>{@code AnyOf} 借 {@link Joiner#allUntil} 拿到"一路命中即取消其余分支"的语义，
 * {@code Minus} 可以并行先算 denied 分支以提前否决。子任务跑在虚拟线程上，
 * {@code Ctx.CURRENT} 这个 {@code ScopedValue} 自动继承过去，无需手工传递。
 *
 * <p>两处与顺序实现严格对齐的语义，都是"不能把故障变成 deny"：
 * <ul>
 *   <li><strong>失败分支必须浮出来。</strong>{@code allUntil} 不会抛子任务异常，
 *       只 filter SUCCESS 就等于把一次数据库故障静默变成"这条路没通"。</li>
 *   <li><strong>但命中优先于失败。</strong>命中是单调的：一旦有分支命中，其余分支
 *       是否失败都不影响结论，此时抛异常反而会把本该 allow 的请求变成 500。</li>
 * </ul>
 *
 * <p>超时是必须的：一条卡住的查询会让 {@code join()} 永久等待，连带整棵子任务树和请求线程。
 *
 * <p>这个类也是"扩展开放"的兑现点：{@code StructuredTaskScope} 的 API 已经改过几轮，
 * 下次再改也只有这一个文件要动。
 */
public final class StructuredFanout implements Fanout {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    /** 默认并发上限。取值参考典型连接池容量，而不是"越大越好"。 */
    private static final int DEFAULT_CONCURRENCY = 32;

    private final Duration timeout;
    private final Semaphore permits;

    /**
     * @param timeout     单次扇出的等待上限
     * @param concurrency 同时在跑的子任务数上限
     */
    public StructuredFanout(Duration timeout, int concurrency) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("扇出超时必须为正");
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("并发上限必须为正");
        }
        this.timeout = timeout;
        this.permits = new Semaphore(concurrency);
    }

    public StructuredFanout(Duration timeout) {
        this(timeout, DEFAULT_CONCURRENCY);
    }

    public StructuredFanout() {
        this(DEFAULT_TIMEOUT, DEFAULT_CONCURRENCY);
    }

    @Override
    public <T> List<T> all(List<Callable<T>> tasks) throws Exception {
        try (var scope = StructuredTaskScope.open(Joiner.<T>allSuccessfulOrThrow(),
                config -> config.withTimeout(timeout))) {
            tasks.forEach(task -> scope.fork(throttled(task)));
            return scope.join().map(Subtask::get).toList();
        }
    }

    @Override
    public <T> List<T> firstMatch(List<Callable<T>> tasks, Predicate<T> hit) throws Exception {
        Predicate<Subtask<? extends T>> stop =
                subtask -> subtask.state() == Subtask.State.SUCCESS && hit.test(subtask.get());
        try (var scope = StructuredTaskScope.open(Joiner.<T>allUntil(stop),
                config -> config.withTimeout(timeout))) {
            tasks.forEach(task -> scope.fork(throttled(task)));
            var completed = scope.join().toList();

            var results = completed.stream()
                    .filter(subtask -> subtask.state() == Subtask.State.SUCCESS)
                    .map(Subtask::get)
                    .toList();
            if (results.stream().anyMatch(hit)) {
                return results;
            }
            // 没有命中，就必须把失败暴露出来——否则这次故障会被读成 deny
            for (var subtask : completed) {
                if (subtask.state() == Subtask.State.FAILED) {
                    var cause = subtask.exception();
                    throw cause instanceof Exception e ? e : new IllegalStateException(cause);
                }
            }
            return results;
        }
    }

    /**
     * 限流包装。
     *
     * <p>虚拟线程廉价，但每个分支背后是一条数据库连接，而递归会让扇出逐层叠加——
     * 不设并发上限时一次 check 就能把连接池抽干，那时故障面是整个进程而不是这一个请求。
     * 信号量在任务体内获取而不是 fork 前，好让取消能正常传播到还在排队的任务。
     */
    private <T> Callable<T> throttled(Callable<T> task) {
        return () -> {
            permits.acquire();
            try {
                return task.call();
            } finally {
                permits.release();
            }
        };
    }
}

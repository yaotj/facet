package facet.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * 用 JMH 的 {@code Runner} API 跑 {@link AuthBenchmark}。
 *
 * <p>跑法（在仓库根目录）：
 * <pre>{@code
 * mvn -pl facet-benchmark exec:exec
 * # 或等价地手动组装 classpath：
 * mvn -pl facet-benchmark dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * java -cp "facet-benchmark/target/classes:$(cat /tmp/cp.txt)" facet.benchmark.BenchmarkMain
 * }</pre>
 *
 * 命令行可覆盖注解里的配置，例如只跑一次、不 fork 的快速验证：
 * <pre>{@code
 * java -cp "facet-benchmark/target/classes:$(cat /tmp/cp.txt)" \
 *     facet.benchmark.BenchmarkMain -f 0 -wi 1 -i 1 -w 200ms -r 200ms
 * }</pre>
 * 有参数时直接转交给 {@code org.openjdk.jmh.Main}（扫描 classpath 上的基准并解析参数）；
 * 无参数时按 {@link AuthBenchmark} 注解里的默认配置跑。
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            org.openjdk.jmh.Main.main(args);
            return;
        }
        new Runner(new OptionsBuilder()
                .include(AuthBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}

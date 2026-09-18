package facet.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件快照断言。
 *
 * <p>判定矩阵会长到几百行，写进 text block 就没人会去读 diff 了。落到文件之后，策略改动
 * 的影响面直接体现在 {@code git diff} 上——这才是"看出这次改动意外放开了什么"的形式。
 *
 * <p>基线缺失时自动写入并<strong>让测试失败</strong>：静默创建基线等于第一次运行永远通过，
 * 那时快照测试只是在记录当前行为，而不是在守护它。
 *
 * <p>批量更新：{@code mvn test -Dfacet.golden.update=true}。
 */
public final class Golden {

    private static final String UPDATE_FLAG = "facet.golden.update";
    private static final Path ROOT = Path.of("src", "test", "resources", "golden");

    private Golden() {
    }

    /**
     * 比对快照，不一致直接抛 {@link AssertionError}（含双向内容，便于定位）。
     *
     * <p>路径相对当前工作目录解析，因此要在被测模块目录下运行——基线属于产生它的那个模块。
     *
     * @param name 基线文件在 {@code src/test/resources/golden} 下的相对路径
     */
    public static void verify(String name, String actual) {
        var file = ROOT.resolve(name);
        boolean update = Boolean.getBoolean(UPDATE_FLAG);

        if (update || !Files.exists(file)) {
            write(file, actual);
            if (update) {
                return;
            }
            throw new AssertionError("基线不存在，已写入 " + file + "；确认内容正确后再跑一次");
        }

        var expected = read(file);
        if (!expected.equals(actual)) {
            throw new AssertionError("快照不一致: " + file
                    + "\n--- 基线 ---\n" + expected
                    + "\n--- 实际 ---\n" + actual
                    + "\n确认变更符合预期后用 -D" + UPDATE_FLAG + "=true 更新基线");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取基线失败: " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("写入基线失败: " + file, e);
        }
    }
}

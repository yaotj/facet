package facet.core.eval;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 排序键的统一比较。
 *
 * <p>存在的唯一理由是跨适配器一致：Java 的 {@code String.compareTo} 是 UTF-16 码元序，
 * PostgreSQL 的 {@code COLLATE "C"} 是 UTF-8 字节序。两者在基本平面的非代理区一致，
 * 但补充平面字符（emoji 之类的 id）与 U+E000–U+FFFF 的相对顺序恰好相反——同一个分页游标
 * 会在两个适配器上翻到不同位置，而这种 bug 只在数据里出现非 ASCII id 时才现形。
 *
 * <p>所以两边统一到 UTF-8 无符号字节序，由这里给出唯一定义。
 */
public final class Keys {

    /** UTF-8 字节序的字符串比较器。 */
    public static final Comparator<String> ORDER = Keys::compare;

    private Keys() {
    }

    /** 按 UTF-8 无符号字节序比较，等价于 PostgreSQL 的 {@code COLLATE "C"}。 */
    public static int compare(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}

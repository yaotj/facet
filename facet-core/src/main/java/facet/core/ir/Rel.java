package facet.core.ir;

/**
 * 关系名（Zanzibar 里的 relation）。
 *
 * <p>注意这里用 {@code String}：只适合 API 边界。JDK 25 还没有 Valhalla value class，
 * 在千万级元组的热路径上 record-of-String 有实打实的对象头与间接开销，届时内部要走
 * symbol table 编码成 int，{@code Rel} 只留在对外契约上。
 */
public record Rel(String name) {

    public Rel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("关系名不能为空");
        }
    }
}

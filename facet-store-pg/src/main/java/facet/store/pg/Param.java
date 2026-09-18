package facet.store.pg;

/**
 * 绑定参数的占位符。
 *
 * <p>编译器不接触请求上下文：主体与一致性坐标编成<strong>标记</strong>而不是具体值，
 * 这样一个 {@code Plan} 只需编译一次，之后每个请求各自绑定。若直接把值编进 SQL，
 * "编译一次执行多次"就成了空话，golden SQL 也会随主体变化而抖动。
 */
public sealed interface Param {

    record Literal(Object value) implements Param {}

    record PrincipalType() implements Param {}

    record PrincipalId() implements Param {}

    /** Userset 主体的关系名；具体主体为 {@code ''}。 */
    record PrincipalRel() implements Param {}

    /** 一致性坐标。 */
    record At() implements Param {}

    /** 请求自带的 CONTEXT 属性。 */
    record ContextAttr(String name) implements Param {}

    /**
     * 分页游标。
     *
     * <p>与 {@link Literal} 分开是为了让 SQL 文本与分页值无关：编译结果按计划形状缓存，
     * 若游标编进 SQL 文本，客户端只要不断变换游标就能把编译缓存撑到 OOM。
     */
    record After() implements Param {}

    /** 分页大小。与 {@link After} 同理。 */
    record Limit() implements Param {}
}

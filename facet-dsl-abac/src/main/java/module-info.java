/**
 * ABAC 编译前端：属性条件的书写方式，产出 {@code Cond} 与 {@code Guarded}。
 *
 * <p>它不引入任何求值能力——条件语言仍然是内核里那个刻意不图灵完备的 {@code Cond}。
 * 前端的价值在于把 {@code Tier}（能否反查）和 {@code Kind}（比较语义）这两个最容易漏填、
 * 漏了又最致命的字段提到函数名上。
 */
module facet.dsl.abac {
    requires facet.core;

    exports facet.dsl.abac;
}

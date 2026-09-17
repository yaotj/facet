/**
 * Facet 内核。
 *
 * <p>没有 {@code requires} —— 只隐式依赖 {@code java.base}。这不是巧合，是设计约束：
 * 内核不知道存储、不知道传输、不知道任何 DSL。多一行 {@code requires} 就要在这里
 * 解释为什么，而不是在 code review 里靠人记得。
 *
 * <p>目前只导出 {@code ir}。{@code spi}（端口）与 {@code eval}（判定接口）在有实际
 * 类型后再逐个开口——空包无法导出，正好挡住"先把包结构摆满"的冲动。
 */
module facet.core {
    exports facet.core.ir;
}

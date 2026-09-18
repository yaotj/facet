/**
 * Facet 内核。
 *
 * <p>没有 {@code requires} —— 只隐式依赖 {@code java.base}。这不是巧合，是设计约束：
 * 内核不知道存储、不知道传输、不知道任何 DSL。多一行 {@code requires} 就要在这里
 * 解释为什么，而不是在 code review 里靠人记得。
 *
 * <p>三个包各守一条边界：
 * <ul>
 *   <li>{@code ir} —— 两套 IR（{@code Perm} 判定、{@code Plan} 反查）与值类型，纯数据；</li>
 *   <li>{@code spi} —— 端口。存储、属性、扇出都在外面，preview API 也关在外面；</li>
 *   <li>{@code eval} —— 求值：check 是解释器，lookupResources 是编译器，方向相反。</li>
 * </ul>
 */
module facet.core {
    exports facet.core.ir;
    exports facet.core.spi;
    exports facet.core.eval;
}

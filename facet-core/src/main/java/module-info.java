/**
 * Facet 内核。
 *
 * <p>没有 {@code requires} —— 只隐式依赖 {@code java.base}。这不是巧合，是设计约束：
 * 内核不知道存储、不知道传输、不知道任何 DSL。多一行 {@code requires} 就要在这里
 * 解释为什么，而不是在 code review 里靠人记得。
 *
 * <p>按职责拆包，每条边界单一：
 * <ul>
 *   <li>{@code ir} —— 两套 IR（{@code Perm} 判定、{@code Plan} 反查）与值类型，纯数据；</li>
 *   <li>{@code schema} —— 已解析 schema 模型（{@code Schema}/{@code RelDef}/{@code TypeDef}）与
 *       加载期校验（{@code Validator}），是 DSL 与 ir-json 的共享契约；</li>
 *   <li>{@code sem} —— 跨适配器必须一致的语义契约：值归一化（{@code Conds}）与排序（{@code Keys}）；</li>
 *   <li>{@code spi} —— 端口（存储/属性/扇出/观测）与端口异常；</li>
 *   <li>{@code spi.decorators} —— 内核自带的端口装饰器（重试、属性预取、串行扇出）；</li>
 *   <li>{@code runtime} —— 适配器必须见的运行时契约（{@code Ctx}/{@code Deadline}）；</li>
 *   <li>{@code eval} —— 求值：check 是解释器，lookupResources 是编译器，方向相反。</li>
 * </ul>
 */
module facet.core {
    exports facet.core.ir;
    exports facet.core.schema;
    exports facet.core.sem;
    exports facet.core.spi;
    exports facet.core.spi.decorators;
    exports facet.core.runtime;
    exports facet.core.eval;
}

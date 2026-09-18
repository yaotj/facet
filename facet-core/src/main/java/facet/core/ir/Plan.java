package facet.core.ir;

import java.util.List;

/**
 * 反查计划：集合代数表达式，交给存储适配器下推执行。
 *
 * <p>它与 {@link Perm} 是两套 IR，方向相反，不能互相复用遍历代码：
 * <ul>
 *   <li>{@code check} 是<strong>解释器</strong>——递归深度由数据决定，编不成静态计划；</li>
 *   <li>{@code lookupResources} 是<strong>编译器</strong>——把 {@link Perm} 降级成这里的算子。</li>
 * </ul>
 *
 * <p>方向翻转最明显的一处是 {@link Perm.Through} → {@link ExpandUp}：check 里是站在
 * doc 上问"我的 folder 是谁"，反查里是从主体命中的 folder 往回展开"这些 folder 下有哪些 doc"。
 */
public sealed interface Plan {

    /** 反向索引扫描：主体在某 {@code rel} 上直接命中的该类型对象。 */
    record ScanReverse(Rel rel, ObjectType type) implements Plan {}

    record Union(List<Plan> inputs) implements Plan {
        public Union {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Union 不能为空——编译器缺陷");
            }
        }
    }

    record Intersect(List<Plan> inputs) implements Plan {
        public Intersect {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Intersect 不能为空——编译器缺陷");
            }
        }
    }

    record Difference(Plan left, Plan right) implements Plan {}

    /**
     * 沿 {@code hop} 反向物化：{@code inner} 产出的每个对象 o'，展开出所有满足
     * {@code x#hop@o'} 的 {@code outer} 类型对象 x。
     *
     * <p>{@code outer} 是必需的：执行器要知道展开到哪个类型上，不能靠全表扫再过滤。
     */
    record ExpandUp(Plan inner, Rel hop, ObjectType outer) implements Plan {}

    /**
     * {@link ExpandUp} 的传递闭包（一跳或多跳，不含 {@code inner} 自身）。
     *
     * <p>{@code Perm.Ref} 的自递归编译到这里。它对应的就是一条 {@code WITH RECURSIVE}——
     * 刻意与 {@code ExpandUp} 分成两个算子而不是加个 flag：适配器能一眼看出该发哪种查询，
     * 而单跳能省掉递归 CTE 的开销。
     */
    record ExpandUpClosure(Plan inner, Rel hop, ObjectType outer) implements Plan {}

    /**
     * 条件过滤。
     *
     * <p>只允许 {@code CONTEXT}/{@code SNAPSHOT} 等级的条件——{@code EXTERNAL} 在
     * schema 加载期就被 {@code Validator} 拒绝，不会走到这里。
     */
    record Filter(Plan input, Cond cond) implements Plan {}

    record Page(Plan input, Cursor after, int limit) implements Plan {
        public Page {
            if (limit <= 0) {
                throw new IllegalArgumentException("分页大小必须为正");
            }
        }
    }
}

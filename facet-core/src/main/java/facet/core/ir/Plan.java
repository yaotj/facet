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

    /** 并集。{@link Perm.AnyOf} 的降级结果。 */
    record Union(List<Plan> inputs) implements Plan {
        /** {@link Perm.AnyOf} 已保证非空，走到这里的空并集只能是编译器缺陷，不是用户输入问题。 */
        public Union {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Union 不能为空——编译器缺陷");
            }
        }
    }

    /** 交集。{@link Perm.AllOf} 的降级结果。 */
    record Intersect(List<Plan> inputs) implements Plan {
        /** 同 {@link Union}：空交集在集合代数里是全集，必须当缺陷处理而不是放行。 */
        public Intersect {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Intersect 不能为空——编译器缺陷");
            }
        }
    }

    /** 差集。{@link Perm.Minus} 的降级结果；deny 的单调语义在这里就是一次不可撤销的集合减法。 */
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

    /**
     * 键游标分页，见 {@link Cursor}。
     *
     * <p>只出现在计划最外层：内层分页会截断后续集合运算的输入，得到的页是错的。
     */
    record Page(Plan input, Cursor after, int limit) implements Plan {
        /** 非正的 limit 在各家 SQL 里语义不一（报错、返回空、忽略），统一在构造期拒绝。 */
        public Page {
            if (limit <= 0) {
                throw new IllegalArgumentException("分页大小必须为正");
            }
        }
    }
}

package facet.core.ir;

import java.util.List;

/**
 * 权限的中间表示。所有模型前端（ACL / RBAC / ABAC / ReBAC）都编译到这里。
 *
 * <p><strong>算子集在此冻结。</strong>新增一个实现 record 是内核 major 版本事件：
 * 因为 {@code sealed} + 穷尽 {@code switch}，任何新算子都会让全部求值器、计划编译器
 * 和存储适配器同时编译失败——爆炸半径由 javac 列出来，不靠文档纪律。
 *
 * <p>算子是<strong>有限树</strong>，递归不在树里而在环境里：{@link Ref} 指回 schema 中的
 * 关系定义，任意深度的层级由名字解析闭合，而不是靠嵌套更多 {@link Through}。
 *
 * <p>各模型的落点：
 * <ul>
 *   <li>ACL —— 只用 {@link Direct}</li>
 *   <li>RBAC —— {@link Direct} + {@link AnyOf}，元组挂在全局单例对象上</li>
 *   <li>ReBAC —— 核心是 {@link Through} + {@link Ref}</li>
 *   <li>ABAC —— 核心是 {@link Guarded}</li>
 * </ul>
 */
public sealed interface Perm {

    /** 直接元组 {@code obj#rel@subject}。对应 Zanzibar 的 {@code _this}。 */
    record Direct(Rel rel) implements Perm {}

    /** 并：任一子项成立。 */
    record AnyOf(List<Perm> terms) implements Perm {
        public AnyOf {
            terms = List.copyOf(terms);
            if (terms.isEmpty()) {
                throw new IllegalArgumentException("AnyOf 不能为空——空并等于 deny all，一定是前端编译错误");
            }
        }
    }

    /** 交：全部子项成立。 */
    record AllOf(List<Perm> terms) implements Perm {
        public AllOf {
            terms = List.copyOf(terms);
            if (terms.isEmpty()) {
                throw new IllegalArgumentException("AllOf 不能为空——空交等于 allow all，一定是前端编译错误");
            }
        }
    }

    /**
     * 差：deny。
     *
     * <p>语义是<strong>单调</strong>的：一旦被 {@code denied} 排除，上层任何算子都不可恢复。
     * 可恢复的 deny 会让判定树不可解释，也会让反查从集合差退化成逐个验证。
     */
    record Minus(Perm base, Perm denied) implements Perm {}

    /**
     * 跳：存在元组 {@code obj#hop@o'}，且 subject 在 {@code o'} 上满足 {@code then}。
     *
     * <p>这就是 tuple-to-userset，一条规则覆盖整棵资源层级（folder 的 viewer 也是其中 doc 的 viewer）。
     * 注意它在两条求值路径上方向相反：{@code check} 里从对象向下追问，反查时则要沿 hop 向上物化。
     */
    record Through(Rel hop, Perm then) implements Perm {}

    /** 条件：ABAC 的唯一挂载点。条件的能力等级决定这条路径能否被反查，见 {@link AttrKey.Tier}。 */
    record Guarded(Perm base, Cond cond) implements Perm {}

    /**
     * 引用：当前对象类型上另一条关系的定义（Zanzibar 的 {@code computed_userset}）。
     *
     * <p>这是递归的唯一入口。{@code folder#view = Direct(viewer) | Through(parent, Ref(view))}
     * 表达任意深度的层级：环由 schema 的名字解析闭合，表达式树本身仍然有限。
     *
     * <p>代价是 schema 加载期必须多做三件事，全在 {@code Validator} 里：引用可解析、
     * 递归环上至少消耗一个元组（否则左递归会栈溢出）、递归环不得穿过 {@code Minus} 的
     * 否定侧（非分层否定没有唯一最小不动点，check 与反查会收敛到不同答案）。
     */
    record Ref(Rel rel) implements Perm {}
}

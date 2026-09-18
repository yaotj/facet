package facet.dsl.abac;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.Perm;

import java.util.Collection;
import java.util.List;

/**
 * ABAC 前端：属性条件的书写方式。
 *
 * <p>手写 IR 是 {@code new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(...), new Cond.Term.Lit(...))}，
 * 三层嵌套里最容易出错的恰恰是最要紧的两处：属性的 {@code Tier}（决定能否反查）和
 * {@code Kind}（决定比较语义）。这里把它们提到函数名上，让声明时就得想清楚。
 *
 * <p>典型用法：
 * {@snippet :
 * var perm = Abac.when(Rebac.direct("viewer"), Abac.all(
 *         Abac.eq(Abac.contextBool("mfa"), true),
 *         Abac.ge(Abac.snapshotNumber("clearance"), 3)));
 * }
 */
public final class Abac {

    private Abac() {
    }

    // ---- 属性声明。Tier 与 Kind 都在名字里，不给"忘了填"的机会 ----

    /** CONTEXT + STRING。随请求传入、无 IO，所以条件停在这一等级时整条路径仍然可反查。 */
    public static AttrKey contextText(String name) {
        return AttrKey.text(name, AttrKey.Tier.CONTEXT);
    }

    /** CONTEXT + NUMBER。序比较按任意精度数值，而不是文本的码元序。 */
    public static AttrKey contextNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.CONTEXT);
    }

    /** CONTEXT + BOOL。只有 {@code eq} / {@code ne} 合法，序比较会被 {@code Validator} 在加载期拒绝。 */
    public static AttrKey contextBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.CONTEXT);
    }

    /** SNAPSHOT + STRING。随元组落库、可建索引，因此能下推进反查的 SQL。 */
    public static AttrKey snapshotText(String name) {
        return AttrKey.text(name, AttrKey.Tier.SNAPSHOT);
    }

    /** SNAPSHOT + NUMBER。声明成 NUMBER 才能让内存与 SQL 两侧的序比较给出同一个答案。 */
    public static AttrKey snapshotNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.SNAPSHOT);
    }

    /** SNAPSHOT + BOOL。同样只允许相等比较。 */
    public static AttrKey snapshotBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.SNAPSHOT);
    }

    /** 需要外部 IO 的属性。声明它就等于放弃这条路径的可反查性，{@code Validator} 会盯着。 */
    public static AttrKey externalText(String name) {
        return AttrKey.text(name, AttrKey.Tier.EXTERNAL);
    }

    /** EXTERNAL + NUMBER。同样只能用在 check 的后置过滤上。 */
    public static AttrKey externalNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.EXTERNAL);
    }

    /** EXTERNAL + BOOL。"是否在职"这类要现问外部系统的开关。 */
    public static AttrKey externalBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.EXTERNAL);
    }

    // ---- 比较 ----

    /** {@code Cmp(EQ, Attr, Lit)}。两侧先按属性的 Kind 归一化再比，所以 {@code "3.0"} 与 {@code 3} 在 NUMBER 上相等。 */
    public static Cond eq(AttrKey key, Object value) {
        return cmp(Cond.Op.EQ, key, value);
    }

    /** {@code Cmp(NE, ...)}，即 EQ 的补。属性缺失时归一为 {@code null}，与任何字面量都算不等，因此 NE 成立。 */
    public static Cond ne(AttrKey key, Object value) {
        return cmp(Cond.Op.NE, key, value);
    }

    /** {@code Cmp(LT, ...)}。属性缺失或无法归一时判为不成立，而不是当作零。BOOL 上非法。 */
    public static Cond lt(AttrKey key, Object value) {
        return cmp(Cond.Op.LT, key, value);
    }

    /** {@code Cmp(LE, ...)}。同 {@code lt} 的缺失语义。BOOL 上非法。 */
    public static Cond le(AttrKey key, Object value) {
        return cmp(Cond.Op.LE, key, value);
    }

    /** {@code Cmp(GT, ...)}。同 {@code lt} 的缺失语义。BOOL 上非法。 */
    public static Cond gt(AttrKey key, Object value) {
        return cmp(Cond.Op.GT, key, value);
    }

    /** {@code Cmp(GE, ...)}。序由属性的 Kind 定：NUMBER 走任意精度数值，STRING 走码元序。 */
    public static Cond ge(AttrKey key, Object value) {
        return cmp(Cond.Op.GE, key, value);
    }

    /** {@code Cmp(PREFIX, ...)}。只对 STRING 属性合法，用在别的 Kind 上会被加载期拒掉。 */
    public static Cond prefix(AttrKey key, String value) {
        return cmp(Cond.Op.PREFIX, key, value);
    }

    /** {@code Cmp(IN, Attr, Lit(List))}。集合在这里拷贝定型，调用方之后改原集合不会影响已建好的条件。 */
    public static Cond in(AttrKey key, Collection<?> values) {
        return new Cond.Cmp(Cond.Op.IN, new Cond.Term.Attr(key), new Cond.Term.Lit(List.copyOf(values)));
    }

    /** 两个属性相比。类型必须一致，否则 {@code Validator} 在加载期拒绝。 */
    public static Cond eq(AttrKey left, AttrKey right) {
        return new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(left), new Cond.Term.Attr(right));
    }

    // ---- 组合 ----

    /** {@code Cond.And}。空参即空合取，恒真——需要"恒真"请直接写 {@link #always()}，别靠这个副作用。 */
    public static Cond all(Cond... terms) {
        return new Cond.And(List.of(terms));
    }

    /** {@code Cond.Or}。空参即空析取，恒假。 */
    public static Cond any(Cond... terms) {
        return new Cond.Or(List.of(terms));
    }

    /** {@code Cond.Not}。取反只作用在条件上，与 {@code Perm.Minus} 的 deny 不是一回事。 */
    public static Cond not(Cond cond) {
        return new Cond.Not(cond);
    }

    /** {@code Cond.Always}。用来占位一个"暂时没有约束"的挂载点，让后续加条件不必改结构。 */
    public static Cond always() {
        return new Cond.Always();
    }

    /** 把条件挂到一个权限上。{@code Guarded} 是 ABAC 在这套 IR 里唯一的挂载点。 */
    public static Perm when(Perm base, Cond cond) {
        return new Perm.Guarded(base, cond);
    }

    private static Cond cmp(Cond.Op op, AttrKey key, Object value) {
        return new Cond.Cmp(op, new Cond.Term.Attr(key), new Cond.Term.Lit(value));
    }
}

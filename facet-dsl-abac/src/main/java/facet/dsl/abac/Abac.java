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

    public static AttrKey contextText(String name) {
        return AttrKey.text(name, AttrKey.Tier.CONTEXT);
    }

    public static AttrKey contextNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.CONTEXT);
    }

    public static AttrKey contextBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.CONTEXT);
    }

    public static AttrKey snapshotText(String name) {
        return AttrKey.text(name, AttrKey.Tier.SNAPSHOT);
    }

    public static AttrKey snapshotNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.SNAPSHOT);
    }

    public static AttrKey snapshotBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.SNAPSHOT);
    }

    /** 需要外部 IO 的属性。声明它就等于放弃这条路径的可反查性，{@code Validator} 会盯着。 */
    public static AttrKey externalText(String name) {
        return AttrKey.text(name, AttrKey.Tier.EXTERNAL);
    }

    public static AttrKey externalNumber(String name) {
        return AttrKey.number(name, AttrKey.Tier.EXTERNAL);
    }

    public static AttrKey externalBool(String name) {
        return AttrKey.bool(name, AttrKey.Tier.EXTERNAL);
    }

    // ---- 比较 ----

    public static Cond eq(AttrKey key, Object value) {
        return cmp(Cond.Op.EQ, key, value);
    }

    public static Cond ne(AttrKey key, Object value) {
        return cmp(Cond.Op.NE, key, value);
    }

    public static Cond lt(AttrKey key, Object value) {
        return cmp(Cond.Op.LT, key, value);
    }

    public static Cond le(AttrKey key, Object value) {
        return cmp(Cond.Op.LE, key, value);
    }

    public static Cond gt(AttrKey key, Object value) {
        return cmp(Cond.Op.GT, key, value);
    }

    public static Cond ge(AttrKey key, Object value) {
        return cmp(Cond.Op.GE, key, value);
    }

    public static Cond prefix(AttrKey key, String value) {
        return cmp(Cond.Op.PREFIX, key, value);
    }

    public static Cond in(AttrKey key, Collection<?> values) {
        return new Cond.Cmp(Cond.Op.IN, new Cond.Term.Attr(key), new Cond.Term.Lit(List.copyOf(values)));
    }

    /** 两个属性相比。类型必须一致，否则 {@code Validator} 在加载期拒绝。 */
    public static Cond eq(AttrKey left, AttrKey right) {
        return new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(left), new Cond.Term.Attr(right));
    }

    // ---- 组合 ----

    public static Cond all(Cond... terms) {
        return new Cond.And(List.of(terms));
    }

    public static Cond any(Cond... terms) {
        return new Cond.Or(List.of(terms));
    }

    public static Cond not(Cond cond) {
        return new Cond.Not(cond);
    }

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

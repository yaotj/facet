package facet.core.eval;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从 schema 静态收集属性键。
 *
 * <p>它回答的是"批量判定前可以预取哪些属性"。答案只包含<strong>本层</strong>的属性——
 * 遇到 {@code Through} 就停止下探，因为跳过去之后要在哪些对象上求值是由数据决定的，
 * 预取无从下手。把这条边界写成代码而不是注释，是为了让预取永远只做它真能做到的事：
 * 预取一份不完整的清单会让人误以为已经批量化，而真正的 N+1 还在更深一层。
 */
public final class Attrs {

    private record Node(ObjectType type, Rel rel) {}

    private Attrs() {
    }

    /**
     * 某个关系在<strong>入口对象自身</strong>上会用到的 SNAPSHOT / EXTERNAL 属性。
     *
     * <p>{@code CONTEXT} 属性不在其中：它们由请求自带，没有 IO，不需要预取。
     */
    public static Set<AttrKey> localKeys(Schema schema, ObjectType type, Rel rel) {
        var found = new LinkedHashSet<AttrKey>();
        walk(schema, type, schema.relation(type, rel).rewrite(), new HashSet<>(), found);
        return Set.copyOf(found);
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，预取这里立刻编译失败。 */
    private static void walk(Schema schema, ObjectType type, Perm perm,
                             Set<Node> seen, Set<AttrKey> found) {
        switch (perm) {
            case Perm.Direct _ -> {
            }
            // Through 之后的求值对象由数据决定，静态收集不到，预取也就无从下手
            case Perm.Through _ -> {
            }
            case Perm.AnyOf(var terms) -> terms.forEach(t -> walk(schema, type, t, seen, found));
            case Perm.AllOf(var terms) -> terms.forEach(t -> walk(schema, type, t, seen, found));
            case Perm.Minus(var base, var denied) -> {
                walk(schema, type, base, seen, found);
                walk(schema, type, denied, seen, found);
            }
            case Perm.Guarded(var base, var cond) -> {
                collect(cond, found);
                walk(schema, type, base, seen, found);
            }
            case Perm.Ref(var referenced) -> {
                if (seen.add(new Node(type, referenced))) {
                    walk(schema, type, schema.relation(type, referenced).rewrite(), seen, found);
                }
            }
        }
    }

    /** 没有 {@code default} 分支：{@code Cond} 加算子，这里立刻编译失败。 */
    private static void collect(Cond cond, Set<AttrKey> found) {
        switch (cond) {
            case Cond.Always _ -> {
            }
            case Cond.Not(var inner) -> collect(inner, found);
            case Cond.And(var terms) -> terms.forEach(t -> collect(t, found));
            case Cond.Or(var terms) -> terms.forEach(t -> collect(t, found));
            case Cond.Cmp(_, var left, var right) -> {
                collect(left, found);
                collect(right, found);
            }
        }
    }

    private static void collect(Cond.Term term, Set<AttrKey> found) {
        switch (term) {
            case Cond.Term.Lit _ -> {
            }
            case Cond.Term.Attr(var key) -> {
                if (key.tier() != AttrKey.Tier.CONTEXT) {
                    found.add(key);
                }
            }
        }
    }
}

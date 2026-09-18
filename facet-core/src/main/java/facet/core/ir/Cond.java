package facet.core.ir;

import java.util.List;

/**
 * 条件表达式。
 *
 * <p>刻意<strong>不</strong>图灵完备：没有循环、没有函数调用、没有用户扩展点。表达力开放到
 * 任意谓词很诱人，但那会同时毁掉可判定性和可反查性——而这两点正是统一内核的全部价值。
 */
public sealed interface Cond {

    /** 恒真。用作 {@code Guarded} 的中性元，便于前端无条件地统一产出 Guarded 结构。 */
    record Always() implements Cond {}

    record Not(Cond inner) implements Cond {}

    record And(List<Cond> terms) implements Cond {
        public And { terms = List.copyOf(terms); }
    }

    record Or(List<Cond> terms) implements Cond {
        public Or { terms = List.copyOf(terms); }
    }

    record Cmp(Op op, Term left, Term right) implements Cond {}

    enum Op { EQ, NE, LT, LE, GT, GE, IN, PREFIX }

    /** 比较项：属性引用或标量字面量。 */
    sealed interface Term {

        record Attr(AttrKey key) implements Term {}

        record Lit(Object scalar) implements Term {}
    }

    /**
     * 条件的能力等级 = 其中所有属性等级的最大值。
     *
     * <p>没有 {@code default} 分支：给 {@code Cond} 加算子，这里立刻编译失败。
     */
    static AttrKey.Tier tierOf(Cond cond) {
        return switch (cond) {
            case Always _ -> AttrKey.Tier.CONTEXT;
            case Not(var inner) -> tierOf(inner);
            case And(var terms) -> maxTier(terms);
            case Or(var terms) -> maxTier(terms);
            case Cmp(_, var left, var right) -> max(tierOf(left), tierOf(right));
        };
    }

    static AttrKey.Tier tierOf(Term term) {
        return switch (term) {
            case Term.Attr(var key) -> key.tier();
            case Term.Lit _ -> AttrKey.Tier.CONTEXT;
        };
    }

    private static AttrKey.Tier maxTier(List<Cond> terms) {
        var acc = AttrKey.Tier.CONTEXT;
        for (var term : terms) {
            acc = max(acc, tierOf(term));
        }
        return acc;
    }

    /** {@code Tier} 的声明顺序即偏序：CONTEXT &lt; SNAPSHOT &lt; EXTERNAL。 */
    private static AttrKey.Tier max(AttrKey.Tier a, AttrKey.Tier b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * 比较的值类型：由参与比较的属性声明，两侧都是字面量时按字符串。
     *
     * <p>类型来自 schema 而不是运行期的值，否则同一条规则会因为数据长相不同而走不同的
     * 比较语义——这类偏差在两个适配器之间尤其致命。
     */
    static AttrKey.Kind kindOf(Cmp cmp) {
        if (cmp.left() instanceof Term.Attr(var key)) {
            return key.kind();
        }
        if (cmp.right() instanceof Term.Attr(var key)) {
            return key.kind();
        }
        return AttrKey.Kind.STRING;
    }
}

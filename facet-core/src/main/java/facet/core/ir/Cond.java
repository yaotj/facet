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

    /** 否定。注意它不降低能力等级：对 {@code EXTERNAL} 属性取反，仍然是一次外部 IO。 */
    record Not(Cond inner) implements Cond {}

    /** 合：全部子项成立。 */
    record And(List<Cond> terms) implements Cond {
        /** 复制成不可变列表：条件树会被判定缓存长期持有，共享可变列表等于让缓存项可被就地篡改。 */
        public And { terms = List.copyOf(terms); }
    }

    /** 或：任一子项成立。 */
    record Or(List<Cond> terms) implements Cond {
        /** 同 {@link And}：构造期定型，杜绝外部持有的列表事后被改。 */
        public Or { terms = List.copyOf(terms); }
    }

    /** 二元比较。比较语义不看值的长相，只看属性侧声明的 {@link AttrKey.Kind}，见 {@link #kindOf}。 */
    record Cmp(Op op, Term left, Term right) implements Cond {}

    /**
     * 允许的比较算子。
     *
     * <p>刻意只保留能原样下推成 SQL 谓词的这几个：{@code PREFIX} 对应 {@code LIKE 'x%'}，
     * 仍然吃得到 B-tree 索引，而通用正则会让反查退化成全表扫。
     */
    enum Op { EQ, NE, LT, LE, GT, GE, IN, PREFIX }

    /** 比较项：属性引用或标量字面量。 */
    sealed interface Term {

        /** 属性引用。取值来源与代价由 {@link AttrKey.Tier} 决定，也正是它抬高整条条件的等级。 */
        record Attr(AttrKey key) implements Term {}

        /** 标量字面量。裸 {@code Object} 是有意的：类型不由这里的值决定，而由参与比较的属性声明。 */
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

    /** 字面量不产生 IO，因此一个比较项的等级只可能由属性引用抬上来。 */
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

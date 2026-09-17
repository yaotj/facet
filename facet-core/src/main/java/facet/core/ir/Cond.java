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
}

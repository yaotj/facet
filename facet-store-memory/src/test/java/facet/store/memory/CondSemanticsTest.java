package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Schema;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件语义里那些只在特定数据下才现形的边界。
 *
 * <p>三条断言对应三个真实的跨适配器分歧来源：数值等值不能看精度、数值白名单两边必须一致、
 * 缺失属性一律不成立。它们在 SQL 侧分别对应 {@code numeric} 等值、守卫正则和 NULL 传播。
 */
class CondSemanticsTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel READ = new Rel("read");

    private final MemoryTupleSource tuples = new MemoryTupleSource()
            .write(Tuple.of(doc(), VIEWER, new ObjectRef(USER, "alice")));
    private final MemoryAttrSource attrs = new MemoryAttrSource();

    /** "3.0" 与 3 必须判等：BigDecimal.equals 比较 scale，会与 SQL 的 numeric 等值分叉。 */
    @Test
    void numberEqualityIgnoresScale() {
        attrs.put(doc(), AttrKey.number("level", AttrKey.Tier.SNAPSHOT), "3.0");

        assertTrue(decide(eq(AttrKey.number("level", AttrKey.Tier.SNAPSHOT), 3)));
    }

    /** 科学计数法不在数值白名单里：SQL 侧的守卫正则也不认，两边必须一致地判不成立。 */
    @Test
    void scientificNotationIsNotNumeric() {
        attrs.put(doc(), AttrKey.number("level", AttrKey.Tier.SNAPSHOT), "1e3");

        assertFalse(decide(eq(AttrKey.number("level", AttrKey.Tier.SNAPSHOT), 1000)));
    }

    /** 缺失属性一律不成立，四个方向的序比较都不能意外满足。 */
    @Test
    void missingAttributeNeverSatisfiesOrdering() {
        var level = AttrKey.number("absent", AttrKey.Tier.SNAPSHOT);

        assertFalse(decide(cmp(Cond.Op.LT, level, 1)));
        assertFalse(decide(cmp(Cond.Op.LE, level, 1)));
        assertFalse(decide(cmp(Cond.Op.GT, level, 1)));
        assertFalse(decide(cmp(Cond.Op.GE, level, 1)));
    }

    /** CONTEXT 属性用布尔字面量与文本 "true" 都应判等：归一化在比较之前发生。 */
    @Test
    void boolNormalizesAcrossRepresentations() {
        var mfa = AttrKey.bool("mfa", AttrKey.Tier.CONTEXT);
        var perm = new Perm.Guarded(new Perm.Direct(VIEWER),
                new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(mfa), new Cond.Term.Lit(true)));

        assertTrue(decide(perm, Map.of("mfa", "true")));
        assertTrue(decide(perm, Map.of("mfa", Boolean.TRUE)));
        assertFalse(decide(perm, Map.of("mfa", "yes")));
    }

    private boolean decide(Cond cond) {
        return decide(new Perm.Guarded(new Perm.Direct(VIEWER), cond), Map.of());
    }

    private boolean decide(Perm perm, Map<String, Object> context) {
        var schema = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(perm, false)))));
        var request = Ctx.Request.of(new SubjectRef.Principal(USER, "alice"))
                .withContextAttrs(context);
        return Ctx.run(request, () -> new Checker(schema, tuples, attrs).check(doc(), READ)).allowed();
    }

    private static Cond eq(AttrKey key, Object value) {
        return cmp(Cond.Op.EQ, key, value);
    }

    private static Cond cmp(Cond.Op op, AttrKey key, Object value) {
        return new Cond.Cmp(op, new Cond.Term.Attr(key), new Cond.Term.Lit(value));
    }

    private static ObjectRef doc() {
        return new ObjectRef(DOC, "x");
    }
}

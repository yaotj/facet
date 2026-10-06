package facet.dsl.abac;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.schema.Schema;
import facet.core.schema.SchemaException;
import facet.core.schema.Validator;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryTupleSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ABAC 前端的验收：条件按声明的类型求值，且非法类型组合在加载期就被拒绝。
 */
class AbacTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel READ = new Rel("read");

    private static final Perm GUARDED = Abac.when(
            new Perm.Direct(VIEWER),
            Abac.all(
                    Abac.eq(Abac.contextBool("mfa"), true),
                    Abac.ge(Abac.snapshotNumber("clearance"), 3)));

    private final Schema schema = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
            VIEWER, Schema.tuples(VIEWER),
            READ, Schema.computed(GUARDED, true)))));

    private final MemoryTupleSource tuples = new MemoryTupleSource()
            .write(Tuple.of(doc("x"), VIEWER, user("alice")));
    private final MemoryAttrSource attrs = new MemoryAttrSource()
            .put(doc("x"), Abac.snapshotNumber("clearance"), 5);
    private final Checker checker = new Checker(schema, tuples, attrs);

    @Test
    void schemaIsValid() {
        Validator.validate(schema);
    }

    @Test
    void allConditionsMustHold() {
        assertTrue(decide(Map.of("mfa", true)));
        assertFalse(decide(Map.of("mfa", false)), "MFA 未通过");
        assertFalse(decide(Map.of()), "属性缺失一律判不成立");
    }

    /** 数值比较按数值，不按字典序：clearance=5 与阈值 3 的比较不能变成 "5" vs "3" 的巧合。 */
    @Test
    void numberComparisonIsNumeric() {
        var strict = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(Abac.when(new Perm.Direct(VIEWER),
                        Abac.ge(Abac.snapshotNumber("clearance"), 10)), true)))));

        assertFalse(Ctx.run(Ctx.Request.of(principal("alice")),
                () -> new Checker(strict, tuples, attrs).check(doc("x"), READ)).allowed());
    }

    @Test
    void boolOrderComparisonIsRejectedAtLoadTime() {
        var bad = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(
                        Abac.when(new Perm.Direct(VIEWER), Abac.gt(Abac.contextBool("mfa"), true)),
                        false)))));

        assertThrows(SchemaException.class, () -> Validator.validate(bad));
    }

    @Test
    void prefixOnNumberIsRejectedAtLoadTime() {
        var bad = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(
                        Abac.when(new Perm.Direct(VIEWER),
                                Abac.prefix(Abac.snapshotNumber("clearance"), "5")),
                        false)))));

        assertThrows(SchemaException.class, () -> Validator.validate(bad));
    }

    @Test
    void mismatchedAttributeKindsAreRejectedAtLoadTime() {
        var bad = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(
                        Abac.when(new Perm.Direct(VIEWER),
                                Abac.eq(Abac.contextText("a"), Abac.contextNumber("b"))),
                        false)))));

        assertThrows(SchemaException.class, () -> Validator.validate(bad));
    }

    /** EXTERNAL 属性挂在声明可反查的路径上，加载期拒绝。 */
    @Test
    void externalAttributeOnListablePathIsRejected() {
        var bad = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(
                        Abac.when(new Perm.Direct(VIEWER), Abac.eq(Abac.externalBool("employed"), true)),
                        true)))));

        assertThrows(SchemaException.class, () -> Validator.validate(bad));
    }

    private boolean decide(Map<String, Object> context) {
        var request = Ctx.Request.of(principal("alice")).withContextAttrs(context);
        return Ctx.run(request, () -> checker.check(doc("x"), READ)).allowed();
    }

    private static SubjectRef.Principal principal(String id) {
        return new SubjectRef.Principal(USER, id);
    }

    private static ObjectRef doc(String id) {
        return new ObjectRef(DOC, id);
    }

    private static ObjectRef user(String id) {
        return new ObjectRef(USER, id);
    }
}

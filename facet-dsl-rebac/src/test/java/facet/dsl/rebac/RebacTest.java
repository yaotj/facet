package facet.dsl.rebac;

import facet.core.eval.SchemaException;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.testkit.FolderScenario;
import org.junit.jupiter.api.Test;

import static facet.dsl.rebac.Rebac.anyOf;
import static facet.dsl.rebac.Rebac.direct;
import static facet.dsl.rebac.Rebac.guarded;
import static facet.dsl.rebac.Rebac.minus;
import static facet.dsl.rebac.Rebac.ref;
import static facet.dsl.rebac.Rebac.through;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DSL 的产出必须与手写 IR 逐字段相同。
 *
 * <p>这是"一个内核多个前端"的验收方式：前端只是书写方式，编译结果要能和内核里其他路径
 * （手写、将来的文本 DSL、RBAC 前端）互换。等价性靠 record 的结构相等一次性断言，
 * 比逐个字段比对更难糊弄过去。
 */
class RebacTest {

    @Test
    void dslProducesTheSameIrAsHandWrittenSchema() {
        var inherited = anyOf(direct("viewer"), through("parent", ref("view")));

        var schema = Rebac.define()
                .type("group", t -> t.tuples("member"))
                .type("folder", t -> t
                        .tuples("viewer")
                        .tuples("parent", "folder")
                        .listable("view", inherited))
                .type("doc", t -> t
                        .tuples("viewer")
                        .tuples("editor")
                        .tuples("banned")
                        .tuples("parent", "folder")
                        .listable("view", inherited)
                        .listable("edit", minus(direct("editor"), direct("banned")))
                        .listable("view_mfa", guarded(ref("view"),
                                new Cond.Cmp(Cond.Op.EQ,
                                        new Cond.Term.Attr(AttrKey.bool("mfa", AttrKey.Tier.CONTEXT)),
                                        new Cond.Term.Lit("true")))))
                .build();

        assertEquals(FolderScenario.SCHEMA, schema);
    }

    /** 构建即校验：左递归在 build() 就炸，不会活到第一次 check。 */
    @Test
    void buildRejectsUnguardedRecursion() {
        var builder = Rebac.define()
                .type("doc", t -> t
                        .tuples("viewer")
                        .listable("view", anyOf(ref("view"), direct("viewer"))));

        assertThrows(SchemaException.class, builder::build);
    }

    /** hop 没声明目标类型时同样在 build() 拒绝。 */
    @Test
    void buildRejectsUndeclaredHopTarget() {
        var builder = Rebac.define()
                .type("doc", t -> t
                        .tuples("viewer")
                        .tuples("parent")
                        .listable("view", through("parent", ref("view"))));

        assertThrows(SchemaException.class, builder::build);
    }

    @Test
    void duplicateRelationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Rebac.define()
                .type("doc", t -> t.tuples("viewer").tuples("viewer")));
    }
}

package facet.store.memory;

import facet.core.eval.EvalException;
import facet.core.eval.Planner;
import facet.core.eval.Schema;
import facet.core.eval.SchemaException;
import facet.core.eval.Validator;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.spi.TupleSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 加载期拒绝与递归形状检查。
 */
class ReversibilityTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType FOLDER = new ObjectType("folder");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel VIEW = new Rel("view");
    private static final Rel PARENT = new Rel("parent");

    private static final Cond EMPLOYED = new Cond.Cmp(
            Cond.Op.EQ,
            new Cond.Term.Attr(AttrKey.bool("employed", AttrKey.Tier.EXTERNAL)),
            new Cond.Term.Lit("true"));

    @Test
    void externalAttributeOnListablePathIsRejectedAtLoadTime() {
        var schema = externalSchema(true);

        assertThrows(SchemaException.class, () -> Validator.validate(schema));
    }

    /** 同一条规则不声明 listable 就是合法的：它只走 check 的最后一跳做后置过滤。 */
    @Test
    void externalAttributeIsFineOnCheckOnlyPath() {
        Validator.validate(externalSchema(false));
    }

    @Test
    void plannerRefusesNonListablePermission() {
        var planner = new Planner(externalSchema(false),
                new TupleSource.Caps(true, false, true, 1024));

        assertThrows(SchemaException.class, () -> planner.plan(DOC, VIEW, Cursor.START, 10));
    }

    /** 没有反向索引就拒绝反查，不做"全表扫后过滤"的静默降级。 */
    @Test
    void plannerRefusesStoreWithoutReverseIndex() {
        var planner = new Planner(externalSchema(true),
                new TupleSource.Caps(false, false, true, 1024));

        assertThrows(EvalException.class, () -> planner.plan(DOC, VIEW, Cursor.START, 10));
    }

    /** 左递归 view = AnyOf[Ref(view), ...] 在加载期被拒绝。 */
    @Test
    void leftRecursionWithoutTupleConsumptionIsRejected() {
        var schema = new Schema(Map.of(
                DOC, new Schema.TypeDef(Map.of(
                        VIEW, Schema.computed(new Perm.AnyOf(List.of(
                                new Perm.Ref(VIEW),
                                new Perm.Direct(VIEWER))), true),
                        VIEWER, Schema.tuples(VIEWER)))));

        assertThrows(SchemaException.class, () -> Validator.validate(schema));
    }

    /**
     * 递归环穿过 {@code Minus} 的<strong>否定侧</strong>时加载期被拒绝：
     * "父链授予 view 则本级不得 view" 是非分层否定，没有唯一最小不动点。
     */
    @Test
    void nonStratifiedNegationIsRejected() {
        var schema = new Schema(Map.of(
                FOLDER, new Schema.TypeDef(Map.of(
                        VIEWER, Schema.tuples(VIEWER),
                        PARENT, Schema.tuples(PARENT, FOLDER),
                        VIEW, Schema.computed(new Perm.Minus(
                                new Perm.Direct(VIEWER),
                                new Perm.Through(PARENT, new Perm.Ref(VIEW))), false)))));

        assertThrows(SchemaException.class, () -> Validator.validate(schema));
    }

    /**
     * 递归走 {@code Minus} 的基础侧是合法的：被否定的 {@code banned} 不在环里，
     * 这是分层否定，有唯一最小模型。校验器不该把它一起拦掉。
     */
    @Test
    void stratifiedNegationInRecursionIsAllowed() {
        var banned = new Rel("banned");
        Validator.validate(new Schema(Map.of(
                FOLDER, new Schema.TypeDef(Map.of(
                        VIEWER, Schema.tuples(VIEWER),
                        PARENT, Schema.tuples(PARENT, FOLDER),
                        banned, Schema.tuples(banned),
                        VIEW, Schema.computed(new Perm.Minus(
                                new Perm.AnyOf(List.of(
                                        new Perm.Direct(VIEWER),
                                        new Perm.Through(PARENT, new Perm.Ref(VIEW)))),
                                new Perm.Direct(banned)), false))))));
    }

    /** 递归定义在存储不支持递归查询时反查被拒绝。 */
    @Test
    void plannerRefusesRecursiveDefinitionWithoutCapability() {
        var schema = new Schema(Map.of(
                FOLDER, new Schema.TypeDef(Map.of(
                        VIEWER, Schema.tuples(VIEWER),
                        PARENT, Schema.tuples(PARENT, FOLDER),
                        VIEW, Schema.computed(new Perm.AnyOf(List.of(
                                new Perm.Direct(VIEWER),
                                new Perm.Through(PARENT, new Perm.Ref(VIEW)))), true)))));

        Validator.validate(schema);
        var planner = new Planner(schema, new TupleSource.Caps(true, false, false, 1024));

        assertThrows(EvalException.class, () -> planner.plan(FOLDER, VIEW, Cursor.START, 10));
    }

    private static Schema externalSchema(boolean listable) {
        return new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                VIEW, new Schema.RelDef(java.util.Set.of(), new Perm.Guarded(
                        new Perm.Direct(VIEWER), EMPLOYED), listable)))));
    }
}

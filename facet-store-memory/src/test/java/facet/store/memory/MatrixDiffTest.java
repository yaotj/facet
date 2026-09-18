package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.eval.Schema;
import facet.core.ir.ObjectRef;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.testkit.MatrixDiff;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.EDITOR;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.principal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略变更的影响分析。
 *
 * <p>这里验证的是一件容易被写错的事：diff 只报<strong>变化的格子</strong>。全量矩阵快照
 * 在策略稍大之后就有几千行，把没变的格子也吐出来等于让人在噪声里找那一行放开的权限——
 * 而放开的权限恰恰是不会有人来报故障的那一类。
 */
class MatrixDiffTest {

    private static final List<SubjectRef> SUBJECTS =
            List.of(principal("alice"), principal("bob"), principal("carol"));
    private static final List<ObjectRef> OBJECTS =
            List.of(doc("private"), doc("readme"));
    private static final List<Rel> RELATIONS = List.of(VIEW, EDIT);

    /** 去掉 banned 那一侧的 schema：alice 会因此拿到 readme 的 edit。 */
    private static final Schema WITHOUT_BAN = withEdit(new Perm.Direct(EDITOR));

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);
    private final MemoryAttrSource attrs = new MemoryAttrSource();

    @Test
    void identicalPoliciesProduceNoChanges() {
        assertTrue(diff(SCHEMA, SCHEMA).isEmpty());
    }

    /** 放开一条权限：diff 里只有这一格。 */
    @Test
    void removingADenyShowsUpAsGranted() {
        assertEquals(
                List.of(new MatrixDiff.Change(principal("alice"), doc("readme"), EDIT, true)),
                diff(SCHEMA, WITHOUT_BAN));
    }

    /** 反向改动是收回，同一格的 granted 翻过来。 */
    @Test
    void addingADenyShowsUpAsRevoked() {
        assertEquals(
                List.of(new MatrixDiff.Change(principal("alice"), doc("readme"), EDIT, false)),
                diff(WITHOUT_BAN, SCHEMA));
    }

    /** 报告把放开的排在前面并单独计数——那是需要先看的部分。 */
    @Test
    void renderCountsBothDirectionsSeparately() {
        assertEquals("""
                新放开 1 条，收回 0 条
                + user:alice | doc:readme | edit
                """, MatrixDiff.render(diff(SCHEMA, WITHOUT_BAN)));
    }

    private List<MatrixDiff.Change> diff(Schema before, Schema after) {
        var first = new Checker(before, tuples, attrs);
        var second = new Checker(after, tuples, attrs);
        return MatrixDiff.of(SUBJECTS, OBJECTS, RELATIONS, Map.of(),
                first::check, second::check);
    }

    /** 只替换 doc 上的 edit 定义，其余保持不变——变更面越小，diff 的断言才越有说服力。 */
    private static Schema withEdit(Perm rewrite) {
        var relations = new LinkedHashMap<>(SCHEMA.types().get(DOC).relations());
        relations.put(EDIT, Schema.computed(rewrite, true));
        var types = new LinkedHashMap<>(SCHEMA.types());
        types.put(DOC, new Schema.TypeDef(relations));
        return new Schema(types);
    }
}

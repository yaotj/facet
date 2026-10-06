package facet.store.memory;

import facet.core.eval.Attrs;
import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.schema.Schema;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量判定：结果必须与逐条一致，代价必须比逐条低。
 *
 * <p>第二条才是重点。一次"这 200 个文档我能看哪些"如果退化成 200 次外部属性调用，
 * 那就是把 N+1 搬进了授权判定——而这种退化从结果上完全看不出来，只能靠计量来断言。
 */
class BulkCheckTest {

    private static final int BATCH = 200;

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel READ = new Rel("read");

    /** EXTERNAL 属性：每次求值都是一次外部调用，最该被批量化的就是它。 */
    private static final AttrKey EMPLOYED = AttrKey.bool("employed", AttrKey.Tier.EXTERNAL);

    private static final Perm GUARDED = new Perm.Guarded(
            new Perm.Direct(VIEWER),
            new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(EMPLOYED), new Cond.Term.Lit(true)));

    /** 不声明 listable：EXTERNAL 属性不允许出现在可反查路径上。 */
    private static final Schema SCHEMA = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
            VIEWER, Schema.tuples(VIEWER),
            READ, Schema.computed(GUARDED, false)))));

    private final List<ObjectRef> docs = docs();
    private final MemoryTupleSource tuples = new MemoryTupleSource();
    private final MemoryAttrSource attrs = new MemoryAttrSource();

    BulkCheckTest() {
        var alice = new ObjectRef(USER, "alice");
        var written = new ArrayList<Tuple>();
        for (int i = 0; i < BATCH; i++) {
            written.add(Tuple.of(docs.get(i), VIEWER, alice));
            // 一半在职、一半不在职，好让结果里两种判定都出现
            attrs.put(docs.get(i), EMPLOYED, i % 2 == 0);
        }
        tuples.write(written);
    }

    @Test
    void localAttributeKeysAreCollected() {
        assertEquals(java.util.Set.of(EMPLOYED), Attrs.localKeys(SCHEMA, DOC, READ));
    }

    @Test
    void bulkAgreesWithOneByOne() {
        var oneByOne = run(checker -> {
            var out = new ArrayList<Boolean>(docs.size());
            docs.forEach(doc -> out.add(checker.check(doc, READ).allowed()));
            return out;
        });
        var bulk = run(checker -> {
            var out = new ArrayList<Boolean>(docs.size());
            checker.checkAll(docs, READ).forEach((_, decision) -> out.add(decision.allowed()));
            return out;
        });

        assertEquals(oneByOne, bulk);
        assertEquals(BATCH / 2, bulk.stream().filter(Boolean::booleanValue).count());
    }

    /** 逐条是 N 次外部读取，批量是 1 次。 */
    @Test
    void bulkCollapsesExternalReads() {
        int before = attrs.externalReads();
        run(checker -> {
            docs.forEach(doc -> checker.check(doc, READ));
            return null;
        });
        int oneByOne = attrs.externalReads() - before;

        int beforeBulk = attrs.externalReads();
        run(checker -> checker.checkAll(docs, READ));
        int bulk = attrs.externalReads() - beforeBulk;

        assertEquals(BATCH, oneByOne, "逐条判定本应每个对象一次外部读取");
        assertEquals(1, bulk, "批量判定应当只发一次外部读取");
    }

    /** 批量保持入参顺序：响应要能和请求逐项对上，否则调用方只能靠 id 再匹配一遍。 */
    @Test
    void bulkPreservesInputOrder() {
        var order = run(checker -> List.copyOf(checker.checkAll(docs, READ).keySet()));

        assertEquals(docs, order);
    }

    @Test
    void emptyBatchIsNotAnError() {
        assertTrue(run(checker -> checker.checkAll(List.of(), READ)).isEmpty());
    }

    private <T> T run(java.util.function.Function<Checker, T> body) {
        var request = Ctx.Request.of(new SubjectRef.Principal(USER, "alice"));
        return Ctx.run(request, () -> body.apply(new Checker(SCHEMA, tuples, attrs)));
    }

    private static List<ObjectRef> docs() {
        var out = new ArrayList<ObjectRef>(BATCH);
        for (int i = 0; i < BATCH; i++) {
            out.add(new ObjectRef(DOC, "d" + i));
        }
        return List.copyOf(out);
    }
}

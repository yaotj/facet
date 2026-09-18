package facet.ir.json;

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
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.FolderScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编解码的验收：往返之后策略必须还是同一份策略。
 *
 * <p>结构相等是最强的断言形式——record 的 {@code equals} 会逐字段比对整棵 IR，
 * 漏编一个算子、丢一个 {@code listable} 标记都会当场失败，比逐字段检查难糊弄得多。
 */
class SchemaJsonTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel READ = new Rel("read");

    /** 七个算子里六个都在这份场景里出现（Direct/Ref/Through/AnyOf/Minus/Guarded）。 */
    @Test
    void roundTripPreservesTheWholeSchema() {
        var encoded = SchemaJson.encode(FolderScenario.SCHEMA);

        assertEquals(FolderScenario.SCHEMA, SchemaJson.decode(encoded));
    }

    /** AllOf 不在标准场景里，单独覆盖，免得它成为唯一没被往返验证过的算子。 */
    @Test
    void roundTripCoversAllOf() {
        var schema = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                new Rel("editor"), Schema.tuples(new Rel("editor")),
                READ, Schema.computed(new Perm.AllOf(List.of(
                        new Perm.Direct(VIEWER), new Perm.Direct(new Rel("editor")))), true)))));

        assertEquals(schema, SchemaJson.decode(SchemaJson.encode(schema)));
    }

    /** 同一份 schema 必须编出同一串字节，否则没法把策略文本纳入版本管理与 diff。 */
    @Test
    void encodingIsDeterministic() {
        assertEquals(SchemaJson.encode(FolderScenario.SCHEMA),
                SchemaJson.encode(FolderScenario.SCHEMA));
    }

    /**
     * 数字字面量往返保的是语义不是对象同一性。
     *
     * <p>JSON 不带 Java 类型信息，{@code Lit(3)} 回来是 {@code Lit(BigDecimal("3"))}。
     * 判定必须一致——比较由 {@code AttrKey.Kind} 决定，两者归一化后是同一个值。
     */
    @Test
    void numericLiteralsSurviveSemantically() {
        var level = AttrKey.number("level", AttrKey.Tier.SNAPSHOT);
        var schema = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(new Perm.Guarded(new Perm.Direct(VIEWER),
                        new Cond.Cmp(Cond.Op.GE, new Cond.Term.Attr(level),
                                new Cond.Term.Lit(3))), true)))));

        var decoded = SchemaJson.decode(SchemaJson.encode(schema));
        var tuples = new MemoryTupleSource()
                .write(Tuple.of(doc(), VIEWER, new ObjectRef(USER, "alice")));
        var attrs = new MemoryAttrSource().put(doc(), level, "5");

        assertEquals(decide(schema, tuples, attrs), decide(decoded, tuples, attrs));
        assertTrue(decide(decoded, tuples, attrs), "level=5 应当满足 >= 3");
    }

    /** 未知算子必须报错：跳过它会得到一份比原意更宽或更严的策略，两种都是事故。 */
    @Test
    void unknownOperatorIsRejected() {
        var json = """
                {"version":1,"types":{"doc":{"read":{"rewrite":{"op":"sometimes","rel":"viewer"}}}}}""";

        var thrown = assertThrows(SchemaJsonException.class, () -> SchemaJson.decode(json));

        assertTrue(thrown.getMessage().contains("sometimes"), thrown.getMessage());
    }

    /** 版本不匹配宁可拒绝，不要尽力解析。 */
    @Test
    void versionMismatchIsRejected() {
        var json = """
                {"version":99,"types":{}}""";

        assertThrows(SchemaJsonException.class, () -> SchemaJson.decode(json));
    }

    /** 从进程外来的 schema 同样要过加载期校验——那才是这几条规则最该生效的地方。 */
    @Test
    void decodeRunsLoadTimeValidation() {
        var json = """
                {"version":1,"types":{"doc":{
                  "viewer":{"rewrite":{"op":"direct","rel":"viewer"}},
                  "read":{"listable":true,"rewrite":{"op":"guarded",
                    "base":{"op":"direct","rel":"viewer"},
                    "cond":{"op":"cmp","cmp":"EQ",
                      "left":{"attr":{"name":"employed","kind":"BOOL","tier":"EXTERNAL"}},
                      "right":{"lit":true}}}}}}}""";

        // EXTERNAL 属性挂在声明可反查的路径上
        assertThrows(facet.core.eval.SchemaException.class, () -> SchemaJson.decode(json));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(SchemaJsonException.class, () -> SchemaJson.decode("{not json"));
    }

    /** 不兜底成 toString：那会把一个类型错误变成一条语义不同的策略。 */
    @Test
    void unsupportedLiteralTypeIsRejected() {
        var schema = new Schema(Map.of(DOC, new Schema.TypeDef(Map.of(
                VIEWER, Schema.tuples(VIEWER),
                READ, Schema.computed(new Perm.Guarded(new Perm.Direct(VIEWER),
                        new Cond.Cmp(Cond.Op.EQ,
                                new Cond.Term.Attr(AttrKey.text("x", AttrKey.Tier.CONTEXT)),
                                new Cond.Term.Lit(new Object()))), false)))));

        assertThrows(SchemaJsonException.class, () -> SchemaJson.encode(schema));
    }

    private static boolean decide(Schema schema, MemoryTupleSource tuples, MemoryAttrSource attrs) {
        return Ctx.run(Ctx.Request.of(new SubjectRef.Principal(USER, "alice")),
                () -> new Checker(schema, tuples, attrs).check(doc(), READ)).allowed();
    }

    private static ObjectRef doc() {
        return new ObjectRef(DOC, "x");
    }
}

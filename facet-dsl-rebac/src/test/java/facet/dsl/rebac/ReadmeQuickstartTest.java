package facet.dsl.rebac;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Explains;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static facet.dsl.rebac.Rebac.anyOf;
import static facet.dsl.rebac.Rebac.direct;
import static facet.dsl.rebac.Rebac.ref;
import static facet.dsl.rebac.Rebac.through;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README 里的五分钟示例，逐行可执行。
 *
 * <p>存在的理由是<strong>文档不会悄悄过期</strong>。README 里的代码片段是新使用者接触这个库的
 * 第一样东西，而一次改签名就能让它编不过——那种错误没有任何构建会发现，只会让第一次尝试的人
 * 直接放弃。这里把它变成一个用例：签名改了编译就失败，输出变了断言就失败。
 *
 * <p>改这个文件时必须同步改 README，反之亦然。连 explain 的那几行文本也一起钉住，
 * 因为 README 里印了它。
 */
class ReadmeQuickstartTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType FOLDER = new ObjectType("folder");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel PARENT = new Rel("parent");
    private static final Rel VIEW = new Rel("view");

    @Test
    void quickstartWorksAsDocumented() {
        // 1. 策略
        var inherited = anyOf(direct("viewer"), through("parent", ref("view")));
        var schema = Rebac.define()
                .type("folder", t -> t
                        .tuples("viewer")
                        .tuples("parent", "folder")
                        .listable("view", inherited))
                .type("doc", t -> t
                        .tuples("viewer")
                        .tuples("parent", "folder")
                        .listable("view", inherited))
                .build();

        // 2. 数据
        var tuples = new MemoryTupleSource().write(
                Tuple.of(new ObjectRef(FOLDER, "eng"), VIEWER, new ObjectRef(USER, "alice")),
                Tuple.of(new ObjectRef(DOC, "readme"), PARENT, new ObjectRef(FOLDER, "eng")));
        var attrs = new MemoryAttrSource();

        // 3. check
        var alice = new SubjectRef.Principal(USER, "alice");
        var checker = new Checker(schema, tuples, attrs);
        var decision = Ctx.run(Ctx.Request.of(alice),
                () -> checker.check(new ObjectRef(DOC, "readme"), VIEW));

        assertTrue(decision.allowed(), "权限从目录继承而来，doc 上没有 alice 的任何元组");
        assertEquals("""
                AnyOf
                  MISS doc:readme#viewer
                  Through(parent)
                    Ref(view)
                      AnyOf
                        HIT folder:eng#viewer
                """, Explains.render(decision.explain()));

        // 4. 反查
        var plan = new Planner(schema, tuples.caps()).plan(DOC, VIEW, Cursor.START, 10);
        var executor = new MemoryPlanExecutor(tuples, attrs);
        var visible = Ctx.run(Ctx.Request.of(alice), () -> executor.execute(plan).toList());

        assertEquals(List.of(new ObjectRef(DOC, "readme")), visible,
                "同一条继承在两条路径上都必须成立");
    }
}

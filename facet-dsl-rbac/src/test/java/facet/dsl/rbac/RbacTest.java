package facet.dsl.rbac;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 前端的验收：编译产物必须能被内核直接求值。
 *
 * <p>断言的是"角色继承靠 {@code Ref} 生效"这件事——root 只有一条 admin 元组，却能通过
 * admin → editor → viewer 的引用链拿到 read。如果继承是在前端展开成元组的，这个用例
 * 会需要三条元组才能通过。
 */
class RbacTest {

    private static final ObjectType DOC = new ObjectType("doc");
    private static final ObjectType USER = new ObjectType("user");
    private static final Rel READ = new Rel("read");
    private static final Rel DELETE = new Rel("delete");

    private final facet.core.eval.Schema schema = Rbac.on("doc")
            .roles("viewer", "editor", "admin")
            .inherits("editor", "viewer")
            .inherits("admin", "editor")
            .permission("read", "viewer")
            .permission("write", "editor")
            .permission("delete", "admin")
            .build();

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(
            Tuple.of(doc("x"), new Rel("admin"), user("root")),
            Tuple.of(doc("x"), new Rel("viewer"), user("guest")));
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Checker checker = new Checker(schema, tuples, attrs);

    @Test
    void seniorRoleInheritsJuniorPermissions() {
        assertTrue(run("root", READ), "admin 应当通过引用链拿到 read");
        assertTrue(run("root", DELETE));
    }

    @Test
    void juniorRoleDoesNotEscalate() {
        assertTrue(run("guest", READ));
        assertFalse(run("guest", DELETE));
    }

    /** 继承链同样可反查：一条 admin 元组要能在 read 的列表里出现。 */
    @Test
    void inheritedPermissionIsListable() {
        var plan = new Planner(schema, tuples.caps()).plan(DOC, READ, Cursor.START, 10);
        var executor = new MemoryPlanExecutor(tuples, attrs);

        assertEquals(List.of(doc("x")), Ctx.run(request("root"), () -> executor.execute(plan).toList()));
    }

    /** 继承成环会被当作"无元组消耗的递归"拒绝——角色继承本就不该有环。 */
    @Test
    void inheritanceCycleIsRejected() {
        var builder = Rbac.on("doc")
                .roles("a", "b")
                .inherits("a", "b")
                .inherits("b", "a")
                .permission("read", "a");

        assertThrows(RuntimeException.class, builder::build);
    }

    @Test
    void unknownRoleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Rbac.on("doc").roles("viewer").permission("read", "editor"));
    }

    @Test
    void permissionNameCollidingWithRoleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Rbac.on("doc").roles("viewer").permission("viewer", "viewer"));
    }

    private boolean run(String who, Rel relation) {
        return Ctx.run(request(who), () -> checker.check(doc("x"), relation)).allowed();
    }

    private static Ctx.Request request(String who) {
        return Ctx.Request.of(new SubjectRef.Principal(USER, who));
    }

    private static ObjectRef doc(String id) {
        return new ObjectRef(DOC, id);
    }

    private static ObjectRef user(String id) {
        return new ObjectRef(USER, id);
    }
}

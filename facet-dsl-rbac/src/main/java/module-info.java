/**
 * RBAC 前端：角色定义 -&gt; {@link facet.core.ir.Perm}。
 *
 * <p>RBAC 不是与 ReBAC 并列的模型，而是它的退化情形：role 就是一个不挂在具体资源上的
 * relation。所以这里不需要任何独立的求值逻辑，只是把角色继承展成 {@code AnyOf}。
 */
module facet.dsl.rbac {
    requires facet.core;
}

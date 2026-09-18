/**
 * RBAC 编译前端：角色与角色继承编译成 {@code Perm} IR。
 *
 * <p>角色继承编成 {@code Ref} 而不是在前端展开成元组：展开会让"给 admin 也加一条 viewer"
 * 变成数据迁移，而引用只是 schema 变更。这也是"多个前端共享一份 IR"的直接收益——
 * RBAC 不需要自己的求值器。
 */
module facet.dsl.rbac {
    requires facet.core;

    exports facet.dsl.rbac;
}

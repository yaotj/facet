/**
 * ReBAC 编译前端：把关系模型的书写方式编译成内核的 {@code Perm} / {@code Schema}。
 *
 * <p>单向依赖 core，内核完全不知道它的存在——"一个内核多个前端"这条架构主张就体现在这里：
 * RBAC / ABAC 前端各自独立，共享的是同一份 IR 而不是同一套代码。
 *
 * <p>形态选的是 Java builder 而不是文本 DSL：零依赖、编译期类型安全，也不需要先定 IR 的
 * 序列化格式。将来要热加载再加文本前端，它同样只产出 {@code Perm}，内核不必改。
 */
module facet.dsl.rebac {
    requires facet.core;

    exports facet.dsl.rebac;
}

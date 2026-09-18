/**
 * PostgreSQL 适配器。
 *
 * <p>它的作用不只是"能用真库"，而是<strong>检验 {@code Plan} 这套算子集能不能真的下推</strong>：
 * 七个算子里最可疑的是 {@code ExpandUpClosure}，"它对应一条 {@code WITH RECURSIVE}" 在这里
 * 从断言变成可运行的 SQL。如果这里映射不出来，要改的是内核 IR，不是适配器。
 *
 * <p>同时它是唯一声明 {@code snapshotRead} 的适配器：元组表带 {@code rev_from/rev_to}
 * 两列，一致性坐标从 {@code Ctx} 取，"写后一致读"这个承诺在这里才第一次被兑现。
 *
 * <p>{@code requires java.sql} 而不是依赖具体驱动：驱动是运行期实现，编译期钉住会把
 * 使用方绑在某个版本上。
 */
module facet.store.pg {
    requires facet.core;
    requires java.sql;

    exports facet.store.pg;
}

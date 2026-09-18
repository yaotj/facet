/**
 * Schema 的 JSON 编解码。
 *
 * <p>独立模块而不是内核的一部分：内核零依赖这条线不能为了序列化破掉，而 IR 的 record
 * 也不该为了迁就某个 JSON 库去挂注解——注解会把"用哪个库"这个部署决策焊进内核。
 * 因此这里是<strong>手写</strong>的 tagged union 编解码，只用 Jackson 的树模型。
 *
 * <p>它让 schema 可以从进程外加载：配置中心下发、PDP 热更新、跨语言共享同一份策略定义。
 * 三个 DSL 前端产出的是同一套 IR，所以任何前端写出来的策略都能经这里落成文本。
 */
module facet.ir.json {
    requires facet.core;
    requires com.fasterxml.jackson.databind;

    exports facet.ir.json;
}

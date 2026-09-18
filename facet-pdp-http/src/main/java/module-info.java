/**
 * driving 适配器：把内核暴露成远程决策点。
 *
 * <p>三个端点对应三件事：{@code check}（单点判定）、{@code lookup-resources}（反查）、
 * {@code relationships}（关系写入）。传输层刻意选 JDK 自带的 {@code jdk.httpserver} +
 * 虚拟线程执行器——每个请求一个虚拟线程，正好承接内核"IO 等待廉价"的前提。
 *
 * <p><strong>鉴权没有默认实现，必须由调用方提供。</strong>一个不鉴权的 PDP 等于把整套授权
 * 系统的答案免费送出去；把它做成可选参数，就一定有人在生产上忘了填。
 *
 * <p>{@code explain} 默认关闭：判定树会暴露关系图（谁在哪个组里、资源怎么挂的），
 * 那是排查用的调试信息，不是给调用方的常规响应。
 */
module facet.pdp.http {
    requires facet.core;
    requires jdk.httpserver;
    requires com.fasterxml.jackson.databind;

    exports facet.pdp.http;
}

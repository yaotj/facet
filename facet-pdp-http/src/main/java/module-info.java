/**
 * HTTP 决策点：driving 侧适配器。
 *
 * <p>暴露的是四个接口而非一个：check、反查资源、反查主体、部分求值。只做 check 的 PDP
 * 会把调用方逼成"先查 100 条再逐条鉴权过滤剩 3 条"，分页从此失真——这是授权服务最
 * 常见的设计缺陷，在接口定义阶段就要堵住。
 */
module facet.pdp.http {
    requires facet.core;
}

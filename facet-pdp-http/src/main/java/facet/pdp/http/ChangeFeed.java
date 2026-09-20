package facet.pdp.http;

import facet.core.ir.Revision;
import facet.core.ir.TupleChange;

/**
 * 变更流端口。
 *
 * <p>与 {@link RelationshipAdmin} 分开，不是为了少写代码，而是因为两者的<strong>受众不同</strong>：
 * 变更流给每个带缓存的客户端用，运维接口只给运维用。合成一个能力，打开变更流就等于把
 * "一次调用清空整个库"也打开了。
 *
 * <p>轮询式，不是推送。库不该发明一套流式协议，而 {@code jdk.httpserver} 上做长轮询要押住
 * 连接与线程。客户端拿 {@code nextFrom} 与当前 HEAD 比较即可决定要不要立刻再拉一次。
 *
 * <p>只有带版本概念的存储能实现它：内存适配器忽略坐标，所以它只能是 {@link #DENIED}。
 */
@FunctionalInterface
public interface ChangeFeed {

    /**
     * 拉取 {@code (from, to]} 的变更。
     *
     * @param to    {@code HEAD} 表示追到当前
     * @param limit 单批行数软上限；实际返回会退到最后一个完整坐标的边界
     */
    TupleChange.Page changes(Revision from, Revision to, int limit);

    /**
     * 不开放变更流。端点返回 405。
     *
     * <p>默认关闭：变更流会把每一条授权变更的完整内容交出去，等价于一份持续的增量导出。
     */
    ChangeFeed DENIED = (from, to, limit) -> {
        throw new UnsupportedOperationException("该 PDP 未开放变更流");
    };
}

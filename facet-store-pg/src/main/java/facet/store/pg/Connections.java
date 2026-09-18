package facet.store.pg;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接来源。
 *
 * <p>刻意不要 {@code DataSource}：适配器需要的只是"给我一条连接"，而 {@code DataSource}
 * 还带着 JNDI（{@code javax.naming}）和连接池语义。连接池是使用方的决策，钉在端口上会把
 * 一个模块系统层面的依赖也一起拖进来。
 */
@FunctionalInterface
public interface Connections {

    /**
     * 默认语句超时（秒）。
     *
     * <p>取 10 秒而不是"不限"：check 逐层一次往返，单层慢到十秒已经说明存储侧出了问题，
     * 继续等只是把故障从数据库扩散到 PDP——每个在等的请求都占着一条连接和一个虚拟线程。
     */
    int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;

    Connection get() throws SQLException;

    /**
     * 单条语句的执行上限（秒），{@code 0} 表示不限。
     *
     * <p>为什么挂在连接来源上而不是每个适配器的构造参数：超时该取多少由部署形态决定
     * （数据库在哪、池多大、SLA 多少），而那正是提供连接的人知道的事。三个适配器共用它，
     * 也就不会出现"元组读有超时、属性读没有"这种一半设了一半没设的状态。
     *
     * <p><strong>这不能替代池层面的获取超时。</strong>它管的是语句执行，管不了
     * {@link #get()} 本身的排队等待——那是连接池的配置项，必须另外设。
     */
    default int queryTimeoutSeconds() {
        return DEFAULT_QUERY_TIMEOUT_SECONDS;
    }
}

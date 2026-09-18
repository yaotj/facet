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

    Connection get() throws SQLException;
}

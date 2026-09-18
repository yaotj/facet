package facet.store.pg;

import java.util.List;

/** 编译产物：SQL 文本 + 按占位符顺序排列的参数标记。 */
public record SqlQuery(String sql, List<Param> params) {

    /** 拷贝参数列表：编译产物会被跨请求缓存共享，外部持有的可变 list 能悄悄改掉绑定顺序。 */
    public SqlQuery {
        params = List.copyOf(params);
    }
}

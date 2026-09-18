package facet.store.pg;

import java.util.List;

/** 编译产物：SQL 文本 + 按占位符顺序排列的参数标记。 */
public record SqlQuery(String sql, List<Param> params) {

    public SqlQuery {
        params = List.copyOf(params);
    }
}

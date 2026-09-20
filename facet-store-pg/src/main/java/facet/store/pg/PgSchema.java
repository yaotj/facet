package facet.store.pg;

import java.util.List;

/**
 * 表结构。
 *
 * <p>两个决策值得写下来：
 * <ul>
 *   <li>{@code subject_rel} 用 {@code ''} 表示"主体是具体对象"，不用 NULL。递归 CTE 要在
 *       这一列上自连接，NULL 语义会让连接条件恒假，而这种 bug 只在有 userset 的数据上才现形。</li>
 *   <li>{@code rev_from}/{@code rev_to} 是时效区间而不是单个 {@code revision}：只记写入版本
 *       的话，快照读能看到"当时已删除"的元组——一致性坐标就成了摆设。</li>
 * </ul>
 */
public final class PgSchema {

    public static final String TUPLES = "facet_tuple";
    public static final String ATTRS = "facet_attr";
    public static final String WATERMARK = "facet_watermark";

    /** 开区间上界，代表"当前仍有效"。 */
    public static final long OPEN = Long.MAX_VALUE;

    private PgSchema() {
    }

    /**
     * 按依赖顺序给出全部 DDL：建表在前，索引与序列在后。每条都带 {@code IF NOT EXISTS}，
     * 因此可以在每次启动时整批无条件执行。
     *
     * <p>刻意不做版本化迁移：这里只增不改，列的演进应当交给外部迁移工具，
     * 由适配器悄悄改列会让运行中的旧版本读到不认识的表。
     */
    public static List<String> ddl() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS facet_tuple (
                  object_type  text   NOT NULL,
                  object_id    text   NOT NULL,
                  relation     text   NOT NULL,
                  subject_type text   NOT NULL,
                  subject_id   text   NOT NULL,
                  subject_rel  text   NOT NULL DEFAULT '',
                  rev_from     bigint NOT NULL,
                  rev_to       bigint NOT NULL DEFAULT 9223372036854775807
                )""",
                // 正向：check 路径的 subjects()/targets()
                """
                CREATE INDEX IF NOT EXISTS facet_tuple_fwd
                  ON facet_tuple (object_type, object_id, relation)""",
                // 反向：反查与 ExpandUp/ExpandUpClosure 的连接键
                """
                CREATE INDEX IF NOT EXISTS facet_tuple_rev
                  ON facet_tuple (subject_type, subject_id, subject_rel, relation, object_type)""",
                // 坐标由序列分配。序列不参与事务回滚，所以坐标会有空洞——单调足够，连续不必要
                "CREATE SEQUENCE IF NOT EXISTS facet_revision AS bigint START 1",
                // 历史回收水位。单行表，存"变更流最早还能覆盖到哪个坐标"。
                // 没有它，回收之后变更流会安静地少报撤销，而客户端缓存据此更新的结果是
                // 已经收回的权限继续放行——一个不会有任何报错的权限泄漏
                """
                CREATE TABLE IF NOT EXISTS facet_watermark (
                  only_row boolean PRIMARY KEY DEFAULT true CHECK (only_row),
                  value    bigint  NOT NULL DEFAULT 0
                )""",
                "INSERT INTO facet_watermark (only_row, value) VALUES (true, 0) ON CONFLICT DO NOTHING",
                """
                CREATE TABLE IF NOT EXISTS facet_attr (
                  object_type text NOT NULL,
                  object_id   text NOT NULL,
                  name        text NOT NULL,
                  value       text NOT NULL,
                  PRIMARY KEY (object_type, object_id, name)
                )""");
    }
}

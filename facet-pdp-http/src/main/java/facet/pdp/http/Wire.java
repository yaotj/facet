package facet.pdp.http;

import java.util.List;
import java.util.Map;

/**
 * HTTP 线格式。
 *
 * <p>刻意用扁平 record，避免依赖任何 Jackson 注解。必填字段在规范构造器里校验：
 * Jackson 对缺失字段填 {@code null}，不在这里挡住就会在业务代码里变成 NPE，
 * 而 NPE 在处理管道里只能映射成 500——一个字段拼错的请求本该是 400。
 */
final class Wire {

    private Wire() {
    }

    /** 对象或主体引用。 */
    record Ref(String type, String id) {

        Ref {
            require(type, "type");
            require(id, "id");
        }
    }

    /** 关系元组。{@code subjectRelation} 非空时表示 userset 授权。 */
    record TupleJson(Ref object, String relation, Ref subject, String subjectRelation) {

        TupleJson {
            require(object, "object");
            require(relation, "relation");
            require(subject, "subject");
        }
    }

    /** {@code POST /v1/check} 请求体。 */
    record CheckRequest(Ref subject,
                        Ref object,
                        String relation,
                        Long at,
                        Map<String, Object> context) {

        CheckRequest {
            require(subject, "subject");
            require(object, "object");
            require(relation, "relation");
        }
    }

    /** {@code POST /v1/check} 响应体。{@code explain} 未开启时为 {@code null}。 */
    record CheckResponse(boolean allowed, String explain) {}

    /** {@code POST /v1/check-bulk} 请求体：一个主体对一批对象的同一个关系。 */
    record BulkCheckRequest(Ref subject,
                            List<Ref> objects,
                            String relation,
                            Long at,
                            Map<String, Object> context) {

        BulkCheckRequest {
            require(subject, "subject");
            require(objects, "objects");
            require(relation, "relation");
        }
    }

    /** 批量判定里的单条结果。 */
    record BulkDecision(Ref object, boolean allowed) {}

    /** {@code POST /v1/check-bulk} 响应体，顺序与请求里的 {@code objects} 一致。 */
    record BulkCheckResponse(List<BulkDecision> results) {}

    /** {@code POST /v1/lookup-resources} 请求体。 */
    record LookupRequest(Ref subject,
                         String objectType,
                         String relation,
                         Long at,
                         String cursor,
                         Integer limit,
                         Map<String, Object> context) {

        LookupRequest {
            require(subject, "subject");
            require(objectType, "objectType");
            require(relation, "relation");
        }
    }

    /** {@code POST /v1/lookup-resources} 响应体。{@code nextCursor} 为 null 表示已到末页。 */
    record LookupResponse(List<Ref> objects, String nextCursor) {}

    /** {@code POST /v1/lookup-subjects} 请求体：谁能对这个对象做这件事。 */
    record LookupSubjectsRequest(Ref object,
                                 String relation,
                                 Long at,
                                 Map<String, Object> context,
                                 Integer limit,
                                 String cursor) {

        LookupSubjectsRequest {
            require(object, "object");
            require(relation, "relation");
        }
    }

    /**
     * {@code POST /v1/lookup-subjects} 响应体。
     *
     * <p>{@code subjects} 是已展开到具体主体的一页，按确定顺序排列。
     *
     * <p>{@code anyOf} 是被授予了通配的主体类型（{@code user:*} 那种）。它<strong>不参与
     * 分页</strong>，每页都完整给出——通配代表一个开放集合，展不成具体主体，而把它省掉会让
     * "谁能看这份文档"漏掉"所有人"。客户端必须把这一列也显示出来，否则权限界面会撒谎。
     */
    record LookupSubjectsResponse(List<Ref> subjects, List<String> anyOf, String nextCursor) {}

    /** {@code POST /v1/relationships} 请求体。 */
    record WriteRequest(List<TupleJson> writes, List<TupleJson> deletes) {}

    /** {@code POST /v1/relationships} 响应体，返回本批变更生效的坐标。 */
    record WriteResponse(long revision) {}

    /**
     * 筛选条件里的引用。
     *
     * <p>不复用 {@link Ref}：那个的规范构造器要求 {@code type} 与 {@code id} 都非空白，
     * 而筛选条件的核心用法恰恰是只钉住一半（"把 doc 这个类型下的元组全导出来"）。
     * 复用它等于让"任意 id"这件事根本表达不出来，所以这里单独开一个不做校验的形状，
     * 至于"是不是约束得太松"由 {@link facet.core.ir.TupleFilter} 那侧去判断。
     */
    record FilterRef(String type, String id) {}

    /** 元组筛选条件。字段为 {@code null} 表示"任意"，语义与 {@code TupleFilter} 一致。 */
    record TupleFilterJson(FilterRef object,
                           String relation,
                           FilterRef subject,
                           String subjectRelation) {}

    /**
     * {@code POST /v1/relationships/read} 请求体。
     *
     * <p>{@code filter} 必填：漏掉它就是"导出全库"，那种意图必须写成显式的空条件对象，
     * 不能是少传一个字段的后果。
     */
    record ReadRequest(TupleFilterJson filter, TupleJson after, Integer limit, Long at) {

        ReadRequest {
            require(filter, "filter");
        }
    }

    /** {@code POST /v1/relationships/read} 响应体。{@code nextCursor} 为 null 表示已到末页。 */
    record ReadResponse(List<TupleJson> tuples, TupleJson nextCursor) {}

    /** {@code POST /v1/relationships/delete} 请求体。 */
    record DeleteWhereRequest(TupleFilterJson filter) {

        DeleteWhereRequest {
            require(filter, "filter");
        }
    }

    /** {@code POST /v1/relationships/delete} 响应体，返回本次撤销生效的坐标。 */
    record DeleteWhereResponse(long revision) {}

    /**
     * {@code POST /v1/watch} 请求体。
     *
     * <p>{@code from} 必填：漏掉它最自然的兜底是"从 0 开始"，那等于把整库历史当成一次增量
     * 推给客户端，而客户端会以为自己只是补了一小段。
     *
     * <p>{@code to} 为 {@code null} 表示追到 HEAD。刻意不提供"一直等到有变更"的语义——
     * 这是轮询接口，等待要由客户端的调度决定，服务端押住连接就成了另一套协议。
     */
    record WatchRequest(Long from, Long to, Integer limit) {

        WatchRequest {
            require(from, "from");
        }
    }

    /**
     * {@code POST /v1/watch} 响应体。
     *
     * <p>{@code nextFrom} 是下一次的起点，<strong>不是</strong>行游标：一次授权变更是原子的，
     * 批次边界只能落在坐标上。{@code complete} 为 {@code false} 表示还没追到上界，
     * 客户端应当立刻再拉一次而不是等到下个轮询周期——中间这段时间里缓存是错的。
     */
    record WatchResponse(List<ChangeJson> changes, long nextFrom, boolean complete) {}

    /** 变更流里的单条变更。{@code created} 为 {@code false} 是撤销。 */
    record ChangeJson(TupleJson tuple, boolean created, long at) {}

    /** 统一错误体。 */
    record ErrorResponse(String error, String message) {}

    /** {@code POST /v1/schema} 响应体。{@code relations} 是新策略里的关系总数，便于核对下发是否完整。 */
    record SchemaResponse(boolean reloaded, int types, int relations) {}

    /**
     * {@code GET /v1/schema} 响应体：当前生效的策略轮廓。
     *
     * <p>用途是运维自查"这个 PDP 现在跑的是哪一版"。下发 schema 的那一侧是只写的，
     * 没有这个端点就只能靠部署记录去猜，而"下发失败但没人发现"恰恰是最安静的故障。
     *
     * <p><strong>刻意不序列化 {@code Perm} 树。</strong>那份编码由 {@code facet-ir-json} 拥有，
     * 在这里再写一份等于让同一个东西有两种编码，两边会各自演进然后分叉——到时候一份 schema
     * 从这个端点读出来、再从那个解码器灌回去就不是同一份了。要完整定义就用 IR JSON。
     */
    record SchemaView(List<TypeView> types) {}

    /** 一个对象类型上的关系列表。 */
    record TypeView(String type, List<RelationView> relations) {}

    /**
     * 一条关系的轮廓。
     *
     * @param computed 有 rewrite 定义（而不仅仅持有原始元组）
     * @param listable 是否声明可反查。它是个架构事实，运维需要看得到
     * @param targets  原始元组主体侧允许的对象类型；计算关系为空
     */
    record RelationView(String relation, boolean computed, boolean listable, List<String> targets) {}

    private static void require(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("缺少必填字段: " + field);
        }
        if (value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("必填字段不能为空白: " + field);
        }
    }
}

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

    /** {@code POST /v1/relationships} 请求体。 */
    record WriteRequest(List<TupleJson> writes, List<TupleJson> deletes) {}

    /** {@code POST /v1/relationships} 响应体，返回本批变更生效的坐标。 */
    record WriteResponse(long revision) {}

    /** 统一错误体。 */
    record ErrorResponse(String error, String message) {}

    /** {@code POST /v1/schema} 响应体。{@code relations} 是新策略里的关系总数，便于核对下发是否完整。 */
    record SchemaResponse(boolean reloaded, int types, int relations) {}

    private static void require(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("缺少必填字段: " + field);
        }
        if (value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("必填字段不能为空白: " + field);
        }
    }
}

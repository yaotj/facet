package facet.pdp.http;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.EvalException;
import facet.core.eval.Explains;
import facet.core.eval.Planner;
import facet.core.eval.Schema;
import facet.core.eval.SchemaException;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.spi.AttrSource;
import facet.core.spi.PlanExecutor;
import facet.core.spi.TupleSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP 决策点。
 *
 * <p>三个端点：{@code POST /v1/check}、{@code POST /v1/lookup-resources}、
 * {@code POST /v1/relationships}。每个请求跑在一个虚拟线程上——这正是内核那套解释器
 * 敢多跳递归的前提：IO 等待廉价。
 *
 * <p>几处刻意的取舍：
 * <ul>
 *   <li><strong>鉴权必填，且读写分权。</strong>没有 {@code Authenticator} 就构造不出服务器；
 *       写端点要求 {@code WRITE} 能力，不与只读客户端共用凭据。</li>
 *   <li><strong>explain 要显式开。</strong>判定树会暴露关系图，默认不返回。</li>
 *   <li><strong>分页与请求体都有服务端上限。</strong>客户端只能要更少不能要更多——否则一个
 *       {@code limit=1000000} 或一个超大 body 就是一次拒绝服务。</li>
 *   <li><strong>写请求返回坐标。</strong>调用方把它带进后续读请求即可获得写后一致读；
 *       这是把一致性责任交回调用方，而不是在服务端猜它想读多新的数据。</li>
 *   <li><strong>对外脱敏、对内留痕。</strong>500 的响应体不带细节（错误消息可能含元组内容），
 *       但异常栈必须落日志，否则编程错误在服务端毫无痕迹。</li>
 * </ul>
 */
public final class PdpServer implements AutoCloseable {

    /**
     * @param port          0 表示由系统分配，便于测试
     * @param schema        已通过 {@code Validator} 的 schema
     * @param maxPageSize   分页硬上限
     * @param maxBodyBytes  请求体硬上限
     * @param exposeExplain 是否允许通过 {@code explain=true} 取回判定树
     */
    public record Config(int port,
                         Schema schema,
                         TupleSource tuples,
                         AttrSource attrs,
                         PlanExecutor executor,
                         RelationshipWriter writer,
                         Authenticator authenticator,
                         int maxPageSize,
                         int maxBodyBytes,
                         boolean exposeExplain) {

        /** 默认请求体上限：1 MiB。写入批量再大也应当分批提交。 */
        public static final int DEFAULT_MAX_BODY = 1 << 20;

        public Config {
            if (authenticator == null) {
                throw new IllegalArgumentException(
                        "必须提供 Authenticator：不鉴权的 PDP 等于公开整套授权系统的答案");
            }
            if (maxPageSize <= 0) {
                throw new IllegalArgumentException("分页上限必须为正");
            }
            if (maxBodyBytes <= 0) {
                throw new IllegalArgumentException("请求体上限必须为正");
            }
        }

        /** 请求体上限取默认值。 */
        public Config(int port, Schema schema, TupleSource tuples, AttrSource attrs,
                      PlanExecutor executor, RelationshipWriter writer,
                      Authenticator authenticator, int maxPageSize, boolean exposeExplain) {
            this(port, schema, tuples, attrs, executor, writer, authenticator,
                    maxPageSize, DEFAULT_MAX_BODY, exposeExplain);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(PdpServer.class.getName());
    /** 停机时给在途请求的收尾时间（秒）。 */
    private static final int DRAIN_SECONDS = 2;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Config config;
    private final Checker checker;
    private final Planner planner;

    private PdpServer(Config config) throws IOException {
        this.config = config;
        this.checker = new Checker(config.schema(), config.tuples(), config.attrs());
        this.planner = new Planner(config.schema(), config.tuples().caps());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(executor);
        server.createContext("/v1/check",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::check));
        server.createContext("/v1/check-bulk",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::checkBulk));
        server.createContext("/v1/lookup-resources",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::lookup));
        server.createContext("/v1/relationships",
                exchange -> handle(exchange, Authenticator.Scope.WRITE, this::write));
        server.createContext("/v1/healthz", PdpServer::healthz);
    }

    /** 启动并开始监听。端口冲突等启动失败会包成 {@link UncheckedIOException}。 */
    public static PdpServer start(Config config) {
        try {
            var pdp = new PdpServer(config);
            pdp.server.start();
            return pdp;
        } catch (IOException e) {
            throw new UncheckedIOException("PDP 启动失败", e);
        }
    }

    /** 实际监听端口。配置为 0 时由此取得系统分配的端口。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        // 给在途请求留收尾时间，再关执行器；只 stop(0) 会把执行器和在途任务留在原地
        server.stop(DRAIN_SECONDS);
        executor.shutdown();
    }

    // ---- 端点 ----

    private Object check(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.CheckRequest.class);
        var subject = subject(request.subject());
        var object = ref(request.object());
        var decision = Ctx.run(context(subject, request.at(), request.context()),
                () -> checker.check(object, new Rel(request.relation())));

        boolean wantExplain = config.exposeExplain() && "true".equals(query(exchange, "explain"));
        return new Wire.CheckResponse(decision.allowed(),
                wantExplain ? Explains.render(decision.explain()) : null);
    }

    /**
     * 批量判定。
     *
     * <p>批量大小复用 {@code maxPageSize}：它和分页是同一个问题——一次请求允许服务端
     * 做多少工作。超限直接拒绝而不是截断，否则调用方会以为剩下的对象都是 deny。
     */
    private Object checkBulk(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.BulkCheckRequest.class);
        if (request.objects().size() > config.maxPageSize()) {
            throw new IllegalArgumentException(
                    "单次批量判定最多 " + config.maxPageSize() + " 个对象，收到 "
                            + request.objects().size());
        }
        var objects = new ArrayList<ObjectRef>(request.objects().size());
        request.objects().forEach(wire -> objects.add(ref(wire)));

        // 整批在同一个 Ctx 里跑：共享 Memo，属性也只预取一次
        var decisions = Ctx.run(
                context(subject(request.subject()), request.at(), request.context()),
                () -> checker.checkAll(objects, new Rel(request.relation())));

        var results = new ArrayList<Wire.BulkDecision>(decisions.size());
        decisions.forEach((object, decision) -> results.add(new Wire.BulkDecision(
                new Wire.Ref(object.type().name(), object.id()), decision.allowed())));
        return new Wire.BulkCheckResponse(results);
    }

    private Object lookup(HttpExchange exchange, byte[] body) throws IOException {        var request = JSON.readValue(body, Wire.LookupRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var cursor = request.cursor() == null ? Cursor.START : new Cursor(request.cursor());
        var plan = planner.plan(new ObjectType(request.objectType()),
                new Rel(request.relation()), cursor, limit);

        var found = Ctx.run(context(subject(request.subject()), request.at(), request.context()),
                () -> config.executor().execute(plan).toList());

        var objects = new ArrayList<Wire.Ref>(found.size());
        found.forEach(obj -> objects.add(new Wire.Ref(obj.type().name(), obj.id())));
        // 只有取满一页才可能有下一页；不足一页就不给游标，省掉客户端一次空请求
        var next = found.size() == limit ? Cursor.keyOf(found.getLast()) : null;
        return new Wire.LookupResponse(objects, next);
    }

    private Object write(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.WriteRequest.class);
        var revision = config.writer().apply(tuples(request.writes()), tuples(request.deletes()));
        return new Wire.WriteResponse(revision.value());
    }

    private static void healthz(HttpExchange exchange) throws IOException {
        try (exchange) {
            respond(exchange, 200, "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- 管道 ----

    @FunctionalInterface
    private interface Endpoint {
        Object apply(HttpExchange exchange, byte[] body) throws IOException;
    }

    private void handle(HttpExchange exchange, Authenticator.Scope scope, Endpoint endpoint)
            throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                error(exchange, 405, "method_not_allowed", "只接受 POST");
                return;
            }
            if (!config.authenticator().allows(
                    exchange.getRequestHeaders().getFirst("Authorization"), scope)) {
                error(exchange, 401, "unauthorized", "凭据无效或权限不足");
                return;
            }
            byte[] body;
            try {
                body = readBody(exchange.getRequestBody());
            } catch (BodyTooLarge e) {
                error(exchange, 413, "payload_too_large", e.getMessage());
                return;
            }
            try {
                respond(exchange, 200, JSON.writeValueAsBytes(endpoint.apply(exchange, body)));
            } catch (JacksonException e) {
                // 反序列化失败是调用方的问题。它是 IOException 的子类，
                // 不单独接住就会逃出 handle，客户端拿到的是空响应而不是 400。
                // 线格式的必填校验抛的 IllegalArgumentException 会被 Jackson 包一层，
                // 把它拆出来，好让调用方知道是哪个字段而不是笼统的"解析失败"。
                var detail = e.getCause() instanceof IllegalArgumentException cause
                        ? cause.getMessage()
                        : "请求体无法解析";
                error(exchange, 400, "bad_request", detail);
            } catch (SchemaException | IllegalArgumentException e) {
                // 策略/请求层面的错误：是调用方的问题，不是服务端故障
                error(exchange, 400, "bad_request", e.getMessage());
            } catch (UnsupportedOperationException e) {
                error(exchange, 405, "not_supported", e.getMessage());
            } catch (EvalException e) {
                error(exchange, 422, "cannot_evaluate", e.getMessage());
            } catch (RuntimeException e) {
                // 对外脱敏（消息可能带元组内容），对内必须留痕
                LOG.log(System.Logger.Level.ERROR, "判定失败", e);
                error(exchange, 500, "internal_error", "判定失败");
            }
        }
    }

    /** 请求体超限。 */
    private static final class BodyTooLarge extends RuntimeException {
        BodyTooLarge(String message) {
            super(message);
        }
    }

    /** 有上限地读取请求体：readAllBytes 不限长度，一个大 body 就能耗尽堆。 */
    private byte[] readBody(InputStream in) throws IOException {
        int limit = config.maxBodyBytes();
        var body = in.readNBytes(limit + 1);
        if (body.length > limit) {
            throw new BodyTooLarge("请求体超过 " + limit + " 字节");
        }
        return body;
    }

    private Ctx.Request context(SubjectRef subject, Long at, Map<String, Object> attrs) {
        var request = Ctx.Request.of(subject);
        if (at != null) {
            request = request.at(new Revision(at));
        }
        return attrs == null ? request : request.withContextAttrs(attrs);
    }

    private static List<Tuple> tuples(List<Wire.TupleJson> wire) {
        if (wire == null) {
            return List.of();
        }
        var out = new ArrayList<Tuple>(wire.size());
        for (var item : wire) {
            var subject = item.subjectRelation() == null
                    ? (SubjectRef) new SubjectRef.Principal(
                            new ObjectType(item.subject().type()), item.subject().id())
                    : new SubjectRef.Userset(ref(item.subject()), new Rel(item.subjectRelation()));
            out.add(new Tuple(ref(item.object()), new Rel(item.relation()), subject));
        }
        return out;
    }

    private static SubjectRef subject(Wire.Ref wire) {
        return new SubjectRef.Principal(new ObjectType(wire.type()), wire.id());
    }

    private static ObjectRef ref(Wire.Ref wire) {
        return new ObjectRef(new ObjectType(wire.type()), wire.id());
    }

    private static String query(HttpExchange exchange, String name) {
        var raw = exchange.getRequestURI().getQuery();
        if (raw == null) {
            return null;
        }
        for (var pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void error(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        respond(exchange, status, JSON.writeValueAsBytes(new Wire.ErrorResponse(code, message)));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

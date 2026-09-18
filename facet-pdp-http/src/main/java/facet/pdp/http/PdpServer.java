package facet.pdp.http;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.EvalException;
import facet.core.eval.Expander;
import facet.core.eval.Explains;
import facet.core.eval.Planner;
import facet.core.eval.Schema;
import facet.core.eval.SchemaException;
import facet.core.eval.Validator;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.spi.AttrSource;
import facet.core.spi.Metrics;
import facet.core.spi.PlanExecutor;
import facet.core.spi.StorageException;
import facet.core.spi.TupleSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * 可选能力。
     *
     * <p>单独成一个 record 而不是继续往 {@link Config} 上加字段：位置参数到十几个之后，
     * 调用点已经没人能看出第 12 个 {@code Duration} 是什么意思了。这里全部有默认值，
     * 用 wither 逐项打开，读起来就是一串"启用了什么"。
     */
    public record Extras(DecisionCache cache,
                         RevisionSource revisions,
                         Duration staleness,
                         SchemaDecoder schemaDecoder,
                         AuditSink audit,
                         Metrics metrics) {

        /** 全关。缓存、陈旧读、远程下发 schema、审计、观测都是要显式打开的能力。 */
        public static final Extras NONE = new Extras(DecisionCache.NONE, RevisionSource.NONE,
                Duration.ZERO, SchemaDecoder.DENIED, AuditSink.NONE, Metrics.NOOP);

        public Extras {
            if (cache == null || revisions == null || staleness == null
                    || schemaDecoder == null || audit == null || metrics == null) {
                throw new IllegalArgumentException("可选能力用 NONE / DENIED / ZERO 表达关闭，不用 null");
            }
            if (staleness.isNegative()) {
                throw new IllegalArgumentException("陈旧窗口不能为负");
            }
        }

        /** 只缓存带具体坐标的请求。 */
        public Extras withCache(DecisionCache sink) {
            return new Extras(sink, revisions, staleness, schemaDecoder, audit, metrics);
        }

        /** 接受有界陈旧，读 HEAD 的请求因此也能进缓存。 */
        public Extras withStaleness(RevisionSource source, Duration window) {
            return new Extras(cache, source, window, schemaDecoder, audit, metrics);
        }

        /** 打开远程下发 schema。 */
        public Extras withSchemaDecoder(SchemaDecoder decoder) {
            return new Extras(cache, revisions, staleness, decoder, audit, metrics);
        }

        public Extras withAudit(AuditSink sink) {
            return new Extras(cache, revisions, staleness, schemaDecoder, sink, metrics);
        }

        /**
         * 装上观测挂点。
         *
         * <p>它会被装进每个请求的 {@code Ctx}，所以实现必须线程安全且足够便宜——
         * 每请求一个虚拟线程，一次深层判定会调它几十次。
         */
        public Extras withMetrics(Metrics sink) {
            return new Extras(cache, revisions, staleness, schemaDecoder, audit, sink);
        }
    }

    /**
     * @param port          0 表示由系统分配，便于测试
     * @param schema        已通过 {@code Validator} 的 schema
     * @param maxPageSize   分页与批量的硬上限
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
                         boolean exposeExplain,
                         Extras extras) {

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
            if (extras == null) {
                throw new IllegalArgumentException("可选能力用 Extras.NONE 表达全关，不用 null");
            }
            // 钉住坐标读就是快照读；存储不支持却配了陈旧窗口，会在第一次请求时才炸
            if (!extras.staleness().isZero() && !tuples.caps().snapshotRead()) {
                throw new IllegalArgumentException(
                        "配置了陈旧窗口，但存储未声明 snapshotRead：钉住坐标读需要快照读能力");
            }
        }

        /** 全部可选能力关闭、请求体上限取默认值。 */
        public Config(int port, Schema schema, TupleSource tuples, AttrSource attrs,
                      PlanExecutor executor, RelationshipWriter writer,
                      Authenticator authenticator, int maxPageSize, boolean exposeExplain) {
            this(port, schema, tuples, attrs, executor, writer, authenticator,
                    maxPageSize, DEFAULT_MAX_BODY, exposeExplain, Extras.NONE);
        }

        /** 全部可选能力关闭，指定请求体上限。 */
        public Config(int port, Schema schema, TupleSource tuples, AttrSource attrs,
                      PlanExecutor executor, RelationshipWriter writer,
                      Authenticator authenticator, int maxPageSize, int maxBodyBytes,
                      boolean exposeExplain) {
            this(port, schema, tuples, attrs, executor, writer, authenticator,
                    maxPageSize, maxBodyBytes, exposeExplain, Extras.NONE);
        }
    }


    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(PdpServer.class.getName());
    /** 停机时给在途请求的收尾时间（秒）。 */
    private static final int DRAIN_SECONDS = 2;
    /** 503 里给出的建议重试间隔（秒）。给一个值，客户端才不会立刻重试把恢复中的存储再压一遍。 */
    private static final int RETRY_AFTER_SECONDS = 1;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Config config;
    /** 当前生效的策略。整体替换而不是逐字段改，在途请求要么全看旧的要么全看新的。 */
    private volatile Policy policy;
    /** 钉住的坐标水位。volatile 就够：过期重取是幂等的，多取一次只是多一次水位查询。 */
    private volatile Pinned pinned;

    /** schema 与由它派生的四个求值器。必须同时替换，否则会用新 schema 配旧 Planner。 */
    private record Policy(Schema schema, Checker checker, Planner planner, Expander expander) {}

    private PdpServer(Config config) throws IOException {
        this.config = config;
        this.policy = policyOf(config.schema());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(executor);
        server.createContext("/v1/check",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::check));
        server.createContext("/v1/check-bulk",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::checkBulk));
        server.createContext("/v1/lookup-resources",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::lookup));
        server.createContext("/v1/lookup-subjects",
                exchange -> handle(exchange, Authenticator.Scope.READ, this::lookupSubjects));
        server.createContext("/v1/relationships",
                exchange -> handle(exchange, Authenticator.Scope.WRITE, this::write));
        server.createContext("/v1/schema",
                exchange -> handle(exchange, Authenticator.Scope.WRITE, this::loadSchema));
        server.createContext("/v1/healthz", PdpServer::healthz);
    }

    private Policy policyOf(Schema schema) {
        return new Policy(schema,
                new Checker(schema, config.tuples(), config.attrs()),
                new Planner(schema, config.tuples().caps()),
                new Expander(schema, config.tuples(), config.attrs()));
    }

    /**
     * 换掉当前生效的策略。
     *
     * <p><strong>必须连带清空判定缓存。</strong>缓存键里有元组坐标但没有策略版本，
     * 策略换了而缓存不清，PDP 会继续按旧策略回答，而且旧键不会自然过期——元组坐标没动。
     *
     * <p>先校验再替换：一份不合法的 schema 不能把正在服务的策略换掉。
     */
    public void reload(Schema schema) {
        Validator.validate(schema);
        policy = policyOf(schema);
        config.extras().cache().clear();
    }

    /** 当前生效的 schema。 */
    public Schema schema() {
        return policy.schema();
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

    /** 停止监听并关闭执行器。先给在途请求留出几秒收尾，因此不是立即返回。 */
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
        var relation = new Rel(request.relation());
        boolean wantExplain = config.exposeExplain() && "true".equals(query(exchange, "explain"));

        var at = resolveAt(request.at());
        var key = cacheKey(subject, object, relation, at, request.context(), wantExplain);
        if (key != null) {
            var hit = config.extras().cache().get(key);
            if (hit != null) {
                // 命中也要留痕：审计记的是"谁问到了什么答案"，缓存是服务端的实现细节。
                // 漏掉命中的那部分，同一串访问在审计里会时有时无，而这取决于缓存是否恰好过期
                config.extras().audit().decided(subject, object, relation, hit, at);
                return new Wire.CheckResponse(hit, null);
            }
        }

        var decision = Ctx.run(context(subject, at, request.context()),
                () -> policy.checker().check(object, relation));
        if (key != null) {
            config.extras().cache().put(key, decision.allowed());
        }
        config.extras().audit().decided(subject, object, relation, decision.allowed(), at);
        return new Wire.CheckResponse(decision.allowed(),
                wantExplain ? Explains.render(decision.explain()) : null);
    }

    /**
     * 批量判定。
     *
     * <p>批量大小复用 {@code maxPageSize}：它和分页是同一个问题——一次请求允许服务端
     * 做多少工作。超限直接拒绝而不是截断，否则调用方会以为剩下的对象都是 deny。
     *
     * <p>先查缓存再把未命中的交给 {@code checkAll}：命中的那部分连属性预取都省掉了，
     * 而未命中的仍然共享同一次预取与同一个 Memo。
     */
    private Object checkBulk(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.BulkCheckRequest.class);
        if (request.objects().size() > config.maxPageSize()) {
            throw new IllegalArgumentException(
                    "单次批量判定最多 " + config.maxPageSize() + " 个对象，收到 "
                            + request.objects().size());
        }
        var subject = subject(request.subject());
        var relation = new Rel(request.relation());
        var at = resolveAt(request.at());

        var order = new ArrayList<ObjectRef>(request.objects().size());
        request.objects().forEach(wire -> order.add(ref(wire)));

        var answers = new LinkedHashMap<ObjectRef, Boolean>();
        var pending = new ArrayList<ObjectRef>();
        for (var object : order) {
            var key = cacheKey(subject, object, relation, at, request.context(), false);
            var hit = key == null ? null : config.extras().cache().get(key);
            if (hit == null) {
                pending.add(object);
            } else {
                answers.put(object, hit);
            }
        }

        if (!pending.isEmpty()) {
            // 整批在同一个 Ctx 里跑：共享 Memo，属性也只预取一次
            var decisions = Ctx.run(context(subject, at, request.context()),
                    () -> policy.checker().checkAll(pending, relation));
            decisions.forEach((object, decision) -> {
                answers.put(object, decision.allowed());
                var key = cacheKey(subject, object, relation, at, request.context(), false);
                if (key != null) {
                    config.extras().cache().put(key, decision.allowed());
                }
            });
        }

        var results = new ArrayList<Wire.BulkDecision>(order.size());
        order.forEach(object -> results.add(new Wire.BulkDecision(
                new Wire.Ref(object.type().name(), object.id()), answers.get(object))));
        return new Wire.BulkCheckResponse(results);
    }

    /**
     * 判定要在哪个坐标上求值。
     *
     * <p>请求给了坐标就用它；没给则看是否配置了陈旧窗口——配了就钉到一个刷新过的水位上。
     * 钉住的坐标同时是缓存键与求值坐标，两者必须一致，否则缓存里存的是另一个世界的答案。
     */
    private Revision resolveAt(Long requested) {
        if (requested != null) {
            return new Revision(requested);
        }
        if (config.extras().staleness().isZero()) {
            return Revision.HEAD;
        }
        var snapshot = pinned;
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.nanos() < config.extras().staleness().toNanos()) {
            return snapshot.revision();
        }
        var fresh = config.extras().revisions().head();
        if (fresh.isHead()) {
            return Revision.HEAD;
        }
        pinned = new Pinned(fresh, now);
        return fresh;
    }

    /** @return 缓存键；不可缓存时返回 {@code null} */
    private DecisionCache.Key cacheKey(SubjectRef subject, ObjectRef object, Rel relation,
                                       Revision at, Map<String, Object> contextAttrs,
                                       boolean wantExplain) {
        if (config.extras().cache() == DecisionCache.NONE || at.isHead() || wantExplain) {
            return null;
        }
        if (contextAttrs != null && !contextAttrs.isEmpty()) {
            // 判定依赖请求自带的属性：塞进键会让键空间爆炸，不塞进去就是缓存污染
            return null;
        }
        return new DecisionCache.Key(subject, object, relation, at);
    }

    /** 钉住的坐标水位。 */
    private record Pinned(Revision revision, long nanos) {}

    private Object lookup(HttpExchange exchange, byte[] body) throws IOException {        var request = JSON.readValue(body, Wire.LookupRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var cursor = request.cursor() == null ? Cursor.START : new Cursor(request.cursor());
        var plan = policy.planner().plan(new ObjectType(request.objectType()),
                new Rel(request.relation()), cursor, limit);

        var found = Ctx.run(
                context(subject(request.subject()), resolveAt(request.at()), request.context()),
                () -> config.executor().execute(plan).toList());

        var objects = new ArrayList<Wire.Ref>(found.size());
        found.forEach(obj -> objects.add(new Wire.Ref(obj.type().name(), obj.id())));
        // 只有取满一页才可能有下一页；不足一页就不给游标，省掉客户端一次空请求
        var next = found.size() == limit ? Cursor.keyOf(found.getLast()) : null;
        return new Wire.LookupResponse(objects, next);
    }

    /**
     * 展开：谁能对这个对象做这件事。
     *
     * <p>与反查方向相反，走的是正向端口，因此不需要反向索引。语义是"在给定上下文下"——
     * 条件里的 CONTEXT 属性来自请求，所以审计想问"如果没过 MFA 呢"，换个上下文再问一次即可。
     */
    private Object lookupSubjects(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.LookupSubjectsRequest.class);
        var object = ref(request.object());
        var relation = new Rel(request.relation());
        // 展开不针对某个主体，上下文里的 principal 只是占位
        var placeholder = new SubjectRef.Principal(object.type(), object.id());

        var found = Ctx.run(context(placeholder, resolveAt(request.at()), request.context()),
                () -> policy.expander().subjects(object, relation));

        var subjects = new ArrayList<Wire.Ref>(found.size());
        found.forEach(subject -> subjects.add(new Wire.Ref(subject.type().name(), subject.id())));
        return new Wire.LookupSubjectsResponse(subjects);
    }

    private Object write(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.WriteRequest.class);
        var revision = config.writer().apply(tuples(request.writes()), tuples(request.deletes()));
        return new Wire.WriteResponse(revision.value());
    }

    /**
     * 远程下发 schema。
     *
     * <p>要 {@code WRITE} 能力：能改 schema 比能改元组的影响面更大——前者改的是授权语义本身。
     * 解码器默认拒绝，所以这个写入面必须被显式打开。
     */
    private Object loadSchema(HttpExchange exchange, byte[] body) {        var schema = config.extras().schemaDecoder().decode(body);
        reload(schema);
        int relations = schema.types().values().stream()
                .mapToInt(type -> type.relations().size())
                .sum();
        return new Wire.SchemaResponse(true, schema.types().size(), relations);
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
            } catch (StorageException e) {
                // 可重试的存储故障给 503 而不是 500：500 的语义是"服务端有 bug"，
                // 网关和客户端不会重试；而连接断开、死锁、语句超时恰恰重试一次就好。
                // 两者混成同一个码，调用方只能在"全都重试"和"全都不重试"之间选，两个都错。
                LOG.log(System.Logger.Level.WARNING, "存储故障，可重试=" + e.retryable(), e);
                if (e.retryable()) {
                    exchange.getResponseHeaders().add("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
                    error(exchange, 503, "storage_unavailable", "存储暂时不可用，请重试");
                } else {
                    error(exchange, 500, "internal_error", "判定失败");
                }
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

    private Ctx.Request context(SubjectRef subject, Revision at, Map<String, Object> attrs) {
        // 观测挂点随请求上下文走：Checker / Expander 都从 Ctx 取，不必在构造期注入
        var request = Ctx.Request.of(subject).withMetrics(config.extras().metrics());
        if (!at.isHead()) {
            request = request.at(at);
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

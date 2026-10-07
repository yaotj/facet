package facet.pdp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Ctx;
import facet.core.runtime.Explains;
import facet.core.schema.Schema;
import facet.core.schema.Validator;
import facet.core.spi.AttrSource;
import facet.core.spi.Metrics;
import facet.core.spi.PlanExecutor;
import facet.core.spi.TupleSource;
import facet.pdp.http.ports.Policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

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
 *
 * <p>本类只负责<strong>路由、编排与装配</strong>：把 HTTP 请求解析成内核调用、把结果装回响应。
 * 路由注册表在 {@link #routes()}（每条路由声明路径、方法、所需能力与端点，统一由 {@link #dispatch}
 * 派发）、线格式映射在 {@link WireCodec}、异常到状态码的映射在 {@link HttpErrorMapper}、缓存与坐标
 * 陈旧策略在 {@link DecisionCachePolicy}、四个求值器的装配在 {@link Policy}——各管各的，互不打扰。
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
                         Metrics metrics,
                         Duration deadline,
                         RelationshipAdmin admin,
                         ChangeFeed changes) {

        /** 全关。缓存、陈旧读、远程下发 schema、审计、观测、期限、运维接口、变更流都是要显式打开的能力。 */
        public static final Extras NONE = new Extras(DecisionCache.NONE, RevisionSource.NONE,
                Duration.ZERO, SchemaDecoder.DENIED, AuditSink.NONE, Metrics.NOOP, Duration.ZERO,
                RelationshipAdmin.DENIED, ChangeFeed.DENIED);

        public Extras {
            if (cache == null || revisions == null || staleness == null
                    || schemaDecoder == null || audit == null || metrics == null
                    || deadline == null || admin == null || changes == null) {
                throw new IllegalArgumentException("可选能力用 NONE / DENIED / ZERO 表达关闭，不用 null");
            }
            if (staleness.isNegative()) {
                throw new IllegalArgumentException("陈旧窗口不能为负");
            }
            if (deadline.isNegative()) {
                throw new IllegalArgumentException("请求期限不能为负；不设期限用 Duration.ZERO");
            }
        }

        /** 只缓存带具体坐标的请求。 */
        public Extras withCache(DecisionCache sink) {
            return new Extras(sink, revisions, staleness, schemaDecoder, audit, metrics, deadline,
                    admin, changes);
        }

        /** 接受有界陈旧，读 HEAD 的请求因此也能进缓存。 */
        public Extras withStaleness(RevisionSource source, Duration window) {
            return new Extras(cache, source, window, schemaDecoder, audit, metrics, deadline,
                    admin, changes);
        }

        /** 打开远程下发 schema。 */
        public Extras withSchemaDecoder(SchemaDecoder decoder) {
            return new Extras(cache, revisions, staleness, decoder, audit, metrics, deadline,
                    admin, changes);
        }

        public Extras withAudit(AuditSink sink) {
            return new Extras(cache, revisions, staleness, schemaDecoder, sink, metrics, deadline,
                    admin, changes);
        }

        /**
         * 装上观测挂点。
         *
         * <p>它会被装进每个请求的 {@code Ctx}，所以实现必须线程安全且足够便宜——
         * 每请求一个虚拟线程，一次深层判定会调它几十次。
         */
        public Extras withMetrics(Metrics sink) {
            return new Extras(cache, revisions, staleness, schemaDecoder, audit, sink, deadline,
                    admin, changes);
        }

        /**
         * 给每个请求设一个墙钟期限，到期返回 {@code 504}。
         *
         * <p>与工作预算（{@code Ctx.DEFAULT_MAX_NODES}）分工不同：预算限总工作量、是确定性的，
         * 挡的是"这个形状会把存储打穿"；期限限墙钟、是不确定性的，挡的是"存储今天比平时慢十倍"。
         * 后者在工作量上完全合规，但调用方已经等不起了。
         *
         * <p>默认关闭，因为它的代价是<strong>同一个请求在空闲时通过、在高负载时超时</strong>。
         * 这个取舍只有知道自己 SLA 形态的人能做。
         *
         * <p>期限会被传导到语句超时与并发扇出的 scope 超时上（见 {@code Deadline#clamp}），
         * 所以它是真的上限，不是"检查点之间的近似"。
         */
        public Extras withDeadline(Duration budget) {
            return new Extras(cache, revisions, staleness, schemaDecoder, audit, metrics, budget,
                    admin, changes);
        }

        /**
         * 打开关系运维接口：按条件读取与批量撤销。
         *
         * <p>默认关闭，因为它和判定端点根本不是同一个影响面。判定端点一次只回答一个问题，
         * 而<strong>读端点翻页下去能把整张授权图导出去</strong>——它不需要调用方事先知道有哪些
         * 元组，这恰恰是判定接口天然的限流器；<strong>删端点一次空条件调用就能清空全库</strong>。
         * 这两件事都应当是部署方明确决定要开的，而不是装上 PDP 就白送。
         */
        public Extras withAdmin(RelationshipAdmin port) {
            return new Extras(cache, revisions, staleness, schemaDecoder, audit, metrics, deadline,
                    port, changes);
        }

        /**
         * 打开变更流：让客户端缓存做精确失效。
         *
         * <p>默认关闭，因为<strong>变更流等价于一份持续的增量导出，会把每一条授权变更的完整
         * 内容交出去</strong>。判定端点要求调用方先知道问什么，运维读端点至少还是一次性的；
         * 而变更流只要一个起点坐标就能持续跟着拉，跟到最后拿到的是整张授权图的演化史。
         *
         * <p>与 {@link #withAdmin} 分开而不是合成一项能力：两者受众不同——变更流给每个带缓存的
         * 客户端用，运维接口只给运维用。合成一项，打开变更流就等于把"一次调用清空全库"也打开了。
         */
        public Extras withChangeFeed(ChangeFeed feed) {
            return new Extras(cache, revisions, staleness, schemaDecoder, audit, metrics, deadline,
                    admin, feed);
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
    /** 当前生效的策略：schema 与由它派生的四个求值器。整体替换而不是逐字段改，在途请求要么全看旧的要么全看新的。 */
    private volatile Policy policy;
    /** 缓存键计算与坐标陈旧钉点，依赖配置里的可选能力，自身维护钉点水位。 */
    private final DecisionCachePolicy cachePolicy;

    private PdpServer(Config config) throws IOException {
        this.config = config;
        this.policy = Policy.of(config.schema(), config.tuples(), config.attrs());
        this.cachePolicy = new DecisionCachePolicy(config.extras().cache(),
                config.extras().revisions(), config.extras().staleness());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(executor);
        // 路由注册表：每条路由声明路径、允许的方法、所需能力与端点。新增一个端点只需往
        // routes() 里加一行，方法校验与鉴权由统一的 dispatch 承担，不会再散落到九处重复注册里。
        // jdk.httpserver 按最长前缀派发，所以 /v1/relationships 与更具体的 read/delete 子路径
        // 各自是注册表里独立的一行，互不干扰
        for (var route : routes()) {
            server.createContext(route.path(), exchange -> dispatch(exchange, route));
        }
        // 存活探针不走鉴权、不读请求体、不映射异常：它的契约与其它端点根本不同，单独注册而不是
        // 硬塞进注册表，免得给探针也套上一层它永远用不到的鉴权与错误映射
        server.createContext("/v1/healthz", PdpServer::healthz);
    }

    /**
     * 路由表。
     *
     * <p>每条路由声明：路径、允许的方法、对该方法授权所需的能力、以及处理端点。绝大多数端点
     * 只接受 {@code POST} 且用 {@code READ} 能力；写端点（{@code /v1/relationships}、
     * {@code /v1/relationships/delete}）用 {@code WRITE}。{@code /v1/schema} 是唯一按方法分权的
     * 路由：{@code GET} 读回当前策略轮廓（{@code READ}）、{@code POST} 下发新策略（{@code WRITE}）——
     * jdk.httpserver 在同一路径上只能注册一个 handler，所以方法分支留在 {@code resolve} 里，
     * 但范围与端点仍在这张表上声明，"只有这一条路放开了 GET"一眼可见。
     */
    private List<Route> routes() {
        var read = Authenticator.Scope.READ;
        var write = Authenticator.Scope.WRITE;
        return List.of(
                Route.of("/v1/check", read, this::check),
                Route.of("/v1/check-bulk", read, this::checkBulk),
                Route.of("/v1/lookup-resources", read, this::lookup),
                Route.of("/v1/lookup-subjects", read, this::lookupSubjects),
                Route.of("/v1/relationships", write, this::write),
                Route.of("/v1/relationships/read", read, this::readRelationships),
                Route.of("/v1/relationships/delete", write, this::deleteRelationships),
                Route.of("/v1/watch", read, this::watch),
                // 按方法分权：GET 读轮廓、POST 下发。resolve 按方法来选范围与端点
                new Route("/v1/schema", Set.of("GET", "POST"),
                        method -> "GET".equals(method)
                                ? new Route.ScopeAndEndpoint(read, this::viewSchema)
                                : new Route.ScopeAndEndpoint(write, this::loadSchema)));
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
        policy = Policy.of(schema, config.tuples(), config.attrs());
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
        var subject = WireCodec.subject(request.subject());
        var object = WireCodec.ref(request.object());
        var relation = new Rel(request.relation());
        boolean wantExplain = config.exposeExplain() && "true".equals(query(exchange, "explain"));

        var at = cachePolicy.resolveAt(request.at());
        var key = cachePolicy.cacheKey(subject, object, relation, at, request.context(), wantExplain);
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
        var subject = WireCodec.subject(request.subject());
        var relation = new Rel(request.relation());
        var at = cachePolicy.resolveAt(request.at());

        var order = new ArrayList<ObjectRef>(request.objects().size());
        request.objects().forEach(wire -> order.add(WireCodec.ref(wire)));

        var answers = new LinkedHashMap<ObjectRef, Boolean>();
        var pending = new ArrayList<ObjectRef>();
        for (var object : order) {
            var key = cachePolicy.cacheKey(subject, object, relation, at, request.context(), false);
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
                var key = cachePolicy.cacheKey(subject, object, relation, at, request.context(), false);
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

    private Object lookup(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.LookupRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var cursor = request.cursor() == null ? Cursor.START : new Cursor(request.cursor());
        var plan = policy.planner().plan(new ObjectType(request.objectType()),
                new Rel(request.relation()), cursor, limit);

        var found = Ctx.run(
                context(WireCodec.subject(request.subject()), cachePolicy.resolveAt(request.at()), request.context()),
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
     *
     * <p>分页上限与反查用同一个 {@code maxPageSize}：结果集大小由数据决定，一份挂在大目录下的
     * 文档"谁能看"可能是三万人。<strong>但要注意这个上限只约束响应，不约束工作量</strong>——
     * 展开必须先把集合算完才能取页，真正兜住成本的是工作预算与期限。
     */
    private Object lookupSubjects(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.LookupSubjectsRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var cursor = request.cursor() == null ? Cursor.START : new Cursor(request.cursor());
        var object = WireCodec.ref(request.object());
        var relation = new Rel(request.relation());
        // 展开不针对某个主体，上下文里的 principal 只是占位。用固定 id 而不是对象的 id：
        // 后者若恰好是 "*"，Principal 的构造器会拒绝，端点就会把一次正常查询报成 400
        var placeholder = new SubjectRef.Principal(object.type(), "facet-expander");

        var found = Ctx.run(context(placeholder, cachePolicy.resolveAt(request.at()), request.context()),
                () -> policy.expander().subjects(object, relation, cursor, limit));

        var principals = found.principals();
        var subjects = new ArrayList<Wire.Ref>(principals.size());
        principals.forEach(subject -> subjects.add(new Wire.Ref(subject.type().name(), subject.id())));
        // 通配主体展不开，单独一列报出来。静默忽略它会让"谁能看这份文档"漏掉"所有人"——
        // 对审计来说，低估访问面是最危险的方向
        var anyOf = found.anyOf().stream().map(ObjectType::name).toList();
        // 只有取满一页才可能有下一页；不足一页就不给游标，省掉客户端一次空请求
        var next = principals.size() == limit ? Cursor.keyOf(principals.getLast()) : null;
        return new Wire.LookupSubjectsResponse(subjects, anyOf, next);
    }

    private Object write(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.WriteRequest.class);
        var revision = config.writer().apply(WireCodec.tuples(request.writes()), WireCodec.tuples(request.deletes()));
        return new Wire.WriteResponse(revision.value());
    }

    /**
     * 按条件读回元组。
     *
     * <p>分页上限与判定端点共用 {@code maxPageSize}：这是运维接口里唯一的成本闸门——
     * 筛选条件可以一个字段都不钉，"导出全库"必须靠翻页而不是靠一次巨大的响应完成。
     *
     * <p>要放进 {@code Ctx} 里跑，是因为适配器（PG）从 {@code Ctx.current().at()} 取坐标来决定
     * 读哪个版本的时效区间。不给上下文，这个端点在 HEAD 与历史坐标之间就没法选。
     */
    private Object readRelationships(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.ReadRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var filter = WireCodec.filter(request.filter());
        var after = request.after() == null ? null : WireCodec.tuple(request.after());
        // 读元组不针对某个主体，上下文里的 principal 只是占位
        var placeholder = new SubjectRef.Principal(new ObjectType("_admin"), "_admin");

        var found = Ctx.run(context(placeholder, cachePolicy.resolveAt(request.at()), null),
                () -> config.extras().admin().read(filter, after, limit));

        var tuples = new ArrayList<Wire.TupleJson>(found.size());
        found.forEach(tuple -> tuples.add(WireCodec.json(tuple)));
        // 只有取满一页才可能有下一页；游标就是最后一条元组本身，下次原样传回 after
        var next = found.size() == limit ? WireCodec.json(found.getLast()) : null;
        return new Wire.ReadResponse(tuples, next);
    }

    /**
     * 按条件批量撤销。
     *
     * <p>不需要 {@code Ctx}：撤销是写操作，坐标由适配器自己分配，不存在"在哪个版本上删"。
     */
    private Object deleteRelationships(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.DeleteWhereRequest.class);
        var revision = config.extras().admin().deleteWhere(WireCodec.filter(request.filter()));
        return new Wire.DeleteWhereResponse(revision.value());
    }

    /**
     * 远程下发 schema。
     *
     * <p>要 {@code WRITE} 能力：能改 schema 比能改元组的影响面更大——前者改的是授权语义本身。
     * 解码器默认拒绝，所以这个写入面必须被显式打开。
     */
    private Object loadSchema(HttpExchange exchange, byte[] body) {
        var schema = config.extras().schemaDecoder().decode(body);
        reload(schema);
        int relations = schema.types().values().stream()
                .mapToInt(type -> type.relations().size())
                .sum();
        return new Wire.SchemaResponse(true, schema.types().size(), relations);
    }

    /**
     * 拉取一段变更流。
     *
     * <p>存在的理由是判定缓存：只靠 TTL 失效，授权变更到生效之间必然有一个窗口，而那个窗口里
     * 被收回的权限仍然放行。客户端记住上次的 {@code nextFrom}，就能把失效做成精确的。
     *
     * <p>分页上限与判定端点共用 {@code maxPageSize}，但它在这里是<strong>软上限</strong>：
     * 批次边界只能落在坐标上，所以实际返回可能少于 limit（见 {@code TupleChange.Page}）。
     *
     * <p>不需要 {@code Ctx.run}：变更流吃的是两个显式坐标，它要读的恰恰是"两个版本之间发生了
     * 什么"，而 {@code Ctx.current().at()} 表达的是"在某一个版本上看世界"——后者答不了前者。
     */
    private Object watch(HttpExchange exchange, byte[] body) throws IOException {
        var request = JSON.readValue(body, Wire.WatchRequest.class);
        int limit = Math.min(
                request.limit() == null ? config.maxPageSize() : Math.max(request.limit(), 1),
                config.maxPageSize());
        var from = new Revision(request.from());
        var to = request.to() == null ? Revision.HEAD : new Revision(request.to());

        var page = config.extras().changes().changes(from, to, limit);

        var changes = new ArrayList<Wire.ChangeJson>(page.changes().size());
        page.changes().forEach(change -> changes.add(new Wire.ChangeJson(
                WireCodec.json(change.tuple()), change.created(), change.at().value())));
        return new Wire.WatchResponse(changes, page.nextFrom().value(), page.complete());
    }

    /**
     * 读回当前生效的策略轮廓。
     *
     * <p>{@code POST} 那一侧是只写的：下发成功与否只能看当次响应，之后就没有任何办法确认
     * "这个 PDP 现在跑的是哪一版"。一次静默失败的下发会让整个集群里有几台按旧策略判定，
     * 而每台都健康、每次判定都有答案。
     *
     * <p>用 {@code READ} 能力而不是 {@code WRITE}：它暴露的是关系图的<em>形状</em>（有哪些类型、
     * 哪些关系可反查），不含任何元组。能问判定的客户端本来就能从判定结果里反推出这些。
     */
    private Object viewSchema(HttpExchange exchange, byte[] body) {
        var schema = policy.schema();
        var types = new ArrayList<Wire.TypeView>(schema.types().size());
        schema.types().forEach((type, def) -> {
            var relations = new ArrayList<Wire.RelationView>(def.relations().size());
            def.relations().forEach((rel, relDef) -> relations.add(new Wire.RelationView(
                    rel.name(), computed(rel, relDef), relDef.listable(),
                    relDef.targets().stream().map(ObjectType::name).sorted().toList())));
            relations.sort(Comparator.comparing(Wire.RelationView::relation));
            types.add(new Wire.TypeView(type.name(), relations));
        });
        // schema 里的两层都是 Map.copyOf，迭代顺序未定义。排一下序，好让两次请求、
        // 两台实例的响应可以直接 diff——否则"这两台跑的是同一版吗"又得靠人去比对
        types.sort(Comparator.comparing(Wire.TypeView::type));
        return new Wire.SchemaView(types);
    }

    /**
     * 这条关系是不是有 rewrite 定义。
     *
     * <p>判据取自 {@code Schema.tuples(rel, ...)}：纯存储关系的 rewrite 恰好是
     * {@code Direct(自己)}，所以"不等于 Direct(本关系名)"就是"有定义"。用 record 的相等性
     * 比较整棵树，而不是 {@code instanceof Perm.Direct}——后者会把 {@code Direct(别的关系)}
     * 这种真正的 rewrite（"viewer 的元组也算 editor"）误报成纯存储关系。
     */
    private static boolean computed(Rel rel, Schema.RelDef def) {
        return !def.rewrite().equals(new Perm.Direct(rel));
    }

    private static void healthz(HttpExchange exchange) throws IOException {
        try (exchange) {
            respond(exchange, 200, "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- 管道 ----

    /** 一个端点的处理入口：拿到已通过鉴权与读体的请求，返回要序列化的结果。 */
    @FunctionalInterface
    private interface Endpoint {
        Object apply(HttpExchange exchange, byte[] body) throws IOException;
    }

    /**
     * 一条路由定义。
     *
     * <p>{@code resolve} 按请求方法选出该路由在该方法下授权所需的 {@link Authenticator.Scope} 与
     * {@link Endpoint}。绝大多数路由只接受 {@code POST}、范围与端点恒定（见 {@link #of}）；
     * {@code /v1/schema} 按 {@code GET}/{@code POST} 选不同范围与端点，所以用完整构造器。
     */
    private record Route(String path, Set<String> methods,
                         Function<String, Route.ScopeAndEndpoint> resolve) {
        /** 该路由在某方法下授权所需的能力与处理端点。 */
        record ScopeAndEndpoint(Authenticator.Scope scope, Endpoint endpoint) {}

        /** 最常见的端点：只接受 {@code POST}，范围与端点恒定。 */
        static Route of(String path, Authenticator.Scope scope, Endpoint endpoint) {
            return new Route(path, Set.of("POST"), _ -> new ScopeAndEndpoint(scope, endpoint));
        }
    }

    /**
     * 统一派发：先校验方法（不在该路由允许集合内即 405），再按方法解析出范围与端点交给
     * {@link #handle}。方法校验收口在这里而不是每个端点里，否则九个端点就要各写一遍同样的 405。
     */
    private void dispatch(HttpExchange exchange, Route route) throws IOException {
        if (!route.methods().contains(exchange.getRequestMethod())) {
            error(exchange, 405, "method_not_allowed", "只接受 " + route.methods());
            return;
        }
        var target = route.resolve().apply(exchange.getRequestMethod());
        handle(exchange, target.scope(), target.endpoint());
    }

    /**
     * 一个端点的统一管道：鉴权、读有上限的请求体、跑端点、把异常映射成状态码。
     *
     * @param scope 该端点要求的能力。做成参数而不是"放开一组方法"：允许的方法一多，
     *              鉴权能力就得跟着方法分叉，而那个分叉一旦出错就是读凭据拿到了写权限
     */
    private void handle(HttpExchange exchange, Authenticator.Scope scope, Endpoint endpoint)
            throws IOException {
        try (exchange) {
            if (!config.authenticator().allows(
                    exchange.getRequestHeaders().getFirst("Authorization"), scope)) {
                error(exchange, 401, "unauthorized", "凭据无效或权限不足");
                return;
            }
            byte[] body;
            try {
                body = readBody(exchange.getRequestBody());
            } catch (Exception e) {
                respond(exchange, HttpErrorMapper.map(e));
                return;
            }
            try {
                respond(exchange, 200, JSON.writeValueAsBytes(endpoint.apply(exchange, body)));
            } catch (Exception e) {
                respond(exchange, HttpErrorMapper.map(e));
            }
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
        var deadline = config.extras().deadline();
        if (!deadline.isZero()) {
            // 期限从这里开始算，也就是从解析完请求体、真正开始求值的那一刻
            request = request.withDeadline(deadline);
        }
        if (!at.isHead()) {
            request = request.at(at);
        }
        return attrs == null ? request : request.withContextAttrs(attrs);
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

    private static void respond(HttpExchange exchange, HttpErrorMapper.Action action) throws IOException {
        if (action.retryAfter()) {
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        }
        error(exchange, action.status(), action.code(), action.message());
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

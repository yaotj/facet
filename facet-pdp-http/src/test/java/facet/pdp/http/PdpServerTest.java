package facet.pdp.http;

import facet.core.eval.Ctx;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleFilter;
import facet.core.spi.Metrics;
import facet.core.spi.TupleSource;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.FolderScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDP 的端到端行为。
 *
 * <p>重点不在 HTTP 细节，而在四条边界：凭据必须验、explain 必须显式开、分页上限由服务端定、
 * 只读部署要给 405 而不是静默丢写。这些都是"看起来能跑但线上会出事"的地方。
 */
class PdpServerTest {

    private static final String TOKEN = "Bearer s3cret";

    private PdpServer pdp;
    private final AtomicReference<Revision> lastWrite = new AtomicReference<>();

    @BeforeEach
    void startServer() {
        pdp = PdpServer.start(config(RelationshipWriter.READ_ONLY, false, 2));
    }

    @AfterEach
    void stopServer() {
        pdp.close();
    }

    private PdpServer.Config config(RelationshipWriter writer, boolean exposeExplain, int maxPage) {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        return new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), writer,
                (authorization, scope) -> TOKEN.equals(authorization), maxPage, exposeExplain);
    }

    @Test
    void missingCredentialsAreRejected() throws IOException {
        var response = post("/v1/check", checkBody("alice", "readme"), null);

        assertEquals(401, response.status());
        assertTrue(response.body().contains("unauthorized"), response.body());
    }

    @Test
    void nonPostIsRejected() throws IOException {
        var connection = open("/v1/check");
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", TOKEN);

        assertEquals(405, connection.getResponseCode());
    }

    @Test
    void checkFollowsTheHierarchy() throws IOException {
        assertTrue(post("/v1/check", checkBody("alice", "deep"), TOKEN).body().contains("\"allowed\":true"));
        assertTrue(post("/v1/check", checkBody("bob", "deep"), TOKEN).body().contains("\"allowed\":false"));
    }

    /** 批量判定：结果顺序与请求一致，好让调用方逐项对上而不必再按 id 匹配一遍。 */
    @Test
    void bulkCheckPreservesRequestOrder() throws IOException {
        var body = """
                {"subject":{"type":"user","id":"alice"},"relation":"view",\
                "objects":[{"type":"doc","id":"private"},{"type":"doc","id":"deep"}]}""";

        var response = post("/v1/check-bulk", body, TOKEN);

        assertEquals(200, response.status(), response.body());
        assertTrue(response.body().indexOf("\"private\"") < response.body().indexOf("\"deep\""),
                response.body());
        assertTrue(response.body().contains("{\"object\":{\"type\":\"doc\",\"id\":\"private\"},"
                + "\"allowed\":false}"), response.body());
        assertTrue(response.body().contains("{\"object\":{\"type\":\"doc\",\"id\":\"deep\"},"
                + "\"allowed\":true}"), response.body());
    }

    /** 批量大小超限直接拒绝，而不是截断——截断会让调用方以为剩下的都是 deny。 */
    @Test
    void oversizedBulkIsRejected() throws IOException {
        var body = """
                {"subject":{"type":"user","id":"alice"},"relation":"view","objects":[\
                {"type":"doc","id":"a"},{"type":"doc","id":"b"},{"type":"doc","id":"c"}]}""";

        var response = post("/v1/check-bulk", body, TOKEN);

        assertEquals(400, response.status(), response.body());
        assertTrue(response.body().contains("最多 2"), response.body());
    }

    /** explain 会暴露关系图，没显式开就不返回——哪怕客户端加了 ?explain=true。 */
    @Test
    void explainIsWithheldUnlessEnabled() throws IOException {
        var withheld = post("/v1/check?explain=true", checkBody("alice", "deep"), TOKEN);
        assertTrue(withheld.body().contains("\"explain\":null"), withheld.body());

        try (var verbose = PdpServer.start(config(RelationshipWriter.READ_ONLY, true, 2))) {
            var response = post(verbose.port(), "/v1/check?explain=true", checkBody("alice", "deep"), TOKEN);
            assertTrue(response.body().contains("Through(parent)"), response.body());
        }
    }

    /** 客户端要 1000 条也只给一页上限，否则一个请求就能拖垮存储。 */
    @Test
    void pageSizeIsCappedByServer() throws IOException {
        var body = """
                {"subject":{"type":"user","id":"alice"},"objectType":"doc","relation":"view","limit":1000}""";

        var response = post("/v1/lookup-resources", body, TOKEN);

        assertEquals(200, response.status());
        assertEquals(2, countOccurrences(response.body(), "\"type\":\"doc\""), response.body());
        assertTrue(response.body().contains("\"nextCursor\":\"doc:readme\""), response.body());
    }

    @Test
    void cursorWalksToTheNextPage() throws IOException {
        var body = """
                {"subject":{"type":"user","id":"alice"},"objectType":"doc","relation":"view",\
                "cursor":"doc:readme","limit":2}""";

        var response = post("/v1/lookup-resources", body, TOKEN);

        assertTrue(response.body().contains("\"id\":\"spec\""), response.body());
        assertFalse(response.body().contains("\"id\":\"deep\""), response.body());
        // 不足一页就不给游标，省掉客户端一次空请求
        assertTrue(response.body().contains("\"nextCursor\":null"), response.body());
    }

    /**
     * 展开端点：谁能对这个对象做这件事。
     *
     * <p>走正向端口，所以不需要反向索引；userset 已经在服务端展开成具体的人——
     * 权限管理界面要显示的是人，不是 {@code group:eng#member}。
     */
    @Test
    void lookupSubjectsExpandsToConcretePrincipals() throws IOException {
        var body = """
                {"object":{"type":"doc","id":"readme"},"relation":"view"}""";

        var response = post("/v1/lookup-subjects", body, TOKEN);

        assertEquals(200, response.status(), response.body());
        assertTrue(response.body().contains("{\"type\":\"user\",\"id\":\"alice\"}"), response.body());
        assertTrue(response.body().contains("{\"type\":\"user\",\"id\":\"carol\"}"), response.body());
    }

    /**
     * 展开也要分页。
     *
     * <p>结果集大小由数据决定：一份挂在大目录下的文档"谁能看"可能是三万人。这个端点此前
     * 没有任何上限，一次审计查询就能拉出一个几十兆的响应——而反查那条路早就要求必须给上限，
     * 两条路的规矩必须一致。
     */
    @Test
    void lookupSubjectsIsPaged() throws IOException {
        var first = post("/v1/lookup-subjects", """
                {"object":{"type":"doc","id":"readme"},"relation":"view","limit":1}""", TOKEN);

        assertEquals(200, first.status(), first.body());
        assertEquals("""
                {"subjects":[{"type":"user","id":"alice"}],"anyOf":[],"nextCursor":"user:alice"}""",
                first.body());

        var second = post("/v1/lookup-subjects", """
                {"object":{"type":"doc","id":"readme"},"relation":"view","limit":1,\
                "cursor":"user:alice"}""", TOKEN);

        assertEquals("""
                {"subjects":[{"type":"user","id":"carol"}],"anyOf":[],"nextCursor":"user:carol"}""",
                second.body());

        // 走到末尾：不足一页就不给游标，省掉客户端一次空请求
        var third = post("/v1/lookup-subjects", """
                {"object":{"type":"doc","id":"readme"},"relation":"view","limit":1,\
                "cursor":"user:carol"}""", TOKEN);

        assertEquals("{\"subjects\":[],\"anyOf\":[],\"nextCursor\":null}", third.body());
    }

    /**
     * 通配主体单独一列报出来，不混进已展开的具体主体里。
     *
     * <p>{@code anyOf} 是这个端点上唯一一个<strong>不参与分页</strong>的字段：它代表一个开放
     * 集合（"所有 user"），落不成具体的人。把它省掉，权限界面就会在"谁能看这份文档"上少报
     * 最要紧的那一行；把它塞进 {@code subjects} 则等于要求服务端把整张用户表读出来——
     * 而那恰恰是通配主体要避免的写放大。
     */
    @Test
    void lookupSubjectsReportsWildcardInAnyOf() throws IOException {
        try (var server = PdpServer.start(withWildcardGrant())) {
            var response = post(server.port(), "/v1/lookup-subjects", """
                    {"object":{"type":"doc","id":"public"},"relation":"view"}""", TOKEN);

            assertEquals(200, response.status(), response.body());
            assertEquals("{\"subjects\":[],\"anyOf\":[\"user\"],\"nextCursor\":null}",
                    response.body());
        }
    }

    /**
     * 装了一条通配授权的服务器。
     *
     * <p>不加进共享 fixture：好几个用例断言的是<strong>整个</strong>响应体，多一条授权就会
     * 把它们变成噪声失败。
     */
    private PdpServer.Config withWildcardGrant() {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES)
                .write(new Tuple(FolderScenario.doc("public"), FolderScenario.VIEWER,
                        new SubjectRef.Wildcard(FolderScenario.USER)));
        var attrs = new MemoryAttrSource();
        return new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10, false);
    }

    /** 客户端要 1000 条也只给服务端的一页上限，和反查同一个规矩。 */
    @Test
    void lookupSubjectsPageSizeIsCappedByServer() throws IOException {
        var response = post("/v1/lookup-subjects", """
                {"object":{"type":"doc","id":"readme"},"relation":"view","limit":1000}""", TOKEN);

        assertEquals(200, response.status(), response.body());
        // 共享测试服务器的 maxPageSize 是 2，两个主体刚好取满
        assertEquals(2, countOccurrences(response.body(), "\"type\":\"user\""), response.body());
    }

    @Test
    void readOnlyDeploymentRefusesWrites() throws IOException {        var response = post("/v1/relationships",
                """
                {"writes":[{"object":{"type":"doc","id":"new"},"relation":"viewer",\
                "subject":{"type":"user","id":"dave"}}]}""", TOKEN);

        assertEquals(405, response.status());
    }

    @Test
    void writeReturnsTheAssignedRevision() throws IOException {
        RelationshipWriter writer = (writes, deletes) -> {
            var revision = new Revision(42);
            lastWrite.set(revision);
            assertEquals(1, writes.size());
            assertEquals(0, deletes.size());
            return revision;
        };

        try (var writable = PdpServer.start(config(writer, false, 2))) {
            var response = post(writable.port(), "/v1/relationships",
                    """
                    {"writes":[{"object":{"type":"doc","id":"new"},"relation":"viewer",\
                    "subject":{"type":"user","id":"dave"}}]}""", TOKEN);

            assertEquals(200, response.status());
            assertTrue(response.body().contains("\"revision\":42"), response.body());
            assertNotNull(lastWrite.get());
        }
    }

    /** 未声明 listable 的关系不能反查，错的是请求而不是服务端。 */
    @Test
    void unlistableRelationIsClientError() throws IOException {
        var body = """
                {"subject":{"type":"user","id":"alice"},"objectType":"doc","relation":"parent"}""";

        var response = post("/v1/lookup-resources", body, TOKEN);

        assertEquals(400, response.status());
        assertTrue(response.body().contains("bad_request"), response.body());
    }

    @Test
    void authenticatorIsMandatory() {
        var tuples = new MemoryTupleSource();
        var attrs = new MemoryAttrSource();

        assertThrows(IllegalArgumentException.class, () -> new PdpServer.Config(0,
                FolderScenario.SCHEMA, tuples, attrs, new MemoryPlanExecutor(tuples, attrs),
                RelationshipWriter.READ_ONLY, null, 10, false));
    }

    /** 带具体坐标的请求进缓存：第二次同样的请求不再落到存储。 */
    @Test
    void concreteRevisionIsCached() throws IOException {
        var counting = new CountingCache();

        try (var server = PdpServer.start(cached(counting))) {
            var body = """
                    {"subject":{"type":"user","id":"alice"},"object":{"type":"doc","id":"deep"},\
                    "relation":"view","at":1}""";

            assertTrue(post(server.port(), "/v1/check", body, TOKEN).body().contains("\"allowed\":true"));
            assertEquals(1, counting.puts);
            assertTrue(post(server.port(), "/v1/check", body, TOKEN).body().contains("\"allowed\":true"));
            assertEquals(1, counting.puts, "第二次应当命中缓存，不再写入");
            assertEquals(1, counting.hits);
        }
    }

    /** 读 HEAD 且没配陈旧窗口时一律不缓存：库不替使用方选择陈旧度。 */
    @Test
    void headRequestsAreNotCached() throws IOException {
        var counting = new CountingCache();

        try (var server = PdpServer.start(cached(counting))) {
            post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN);
            post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN);

            assertEquals(0, counting.puts);
        }
    }

    /** 带 CONTEXT 属性的请求绕过缓存：判定依赖请求自带属性，缓存它就是污染。 */
    @Test
    void contextAttributesBypassCache() throws IOException {
        var counting = new CountingCache();

        try (var server = PdpServer.start(cached(counting))) {
            var body = """
                    {"subject":{"type":"user","id":"alice"},"object":{"type":"doc","id":"deep"},\
                    "relation":"view","at":1,"context":{"mfa":"true"}}""";

            post(server.port(), "/v1/check", body, TOKEN);

            assertEquals(0, counting.puts);
        }
    }

    /** 陈旧窗口需要快照读能力，配置期就该拒绝而不是等第一次请求才炸。 */
    @Test
    void stalenessRequiresSnapshotRead() {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();

        assertThrows(IllegalArgumentException.class, () -> new PdpServer.Config(0,
                FolderScenario.SCHEMA, tuples, attrs, new MemoryPlanExecutor(tuples, attrs),
                RelationshipWriter.READ_ONLY, (auth, scope) -> true, 10,
                PdpServer.Config.DEFAULT_MAX_BODY, false,
                PdpServer.Extras.NONE.withStaleness(RevisionSource.NONE, Duration.ofSeconds(5))));
    }

    /** 有界缓存必须淘汰：键里含请求带来的对象 id，无界就是客户端可控的内存泄漏。 */
    @Test
    void boundedCacheEvicts() {        var cache = DecisionCache.bounded(2);
        var keys = new java.util.ArrayList<DecisionCache.Key>();
        for (int i = 0; i < 3; i++) {
            var key = new DecisionCache.Key(FolderScenario.principal("alice"),
                    FolderScenario.doc("d" + i), FolderScenario.VIEW, new Revision(1));
            keys.add(key);
            cache.put(key, true);
        }

        assertNull(cache.get(keys.get(0)), "最早的键应被淘汰");
        assertEquals(Boolean.TRUE, cache.get(keys.get(2)));
    }

    /**
     * 换 schema 必须连带清缓存。
     *
     * <p>缓存键里有元组坐标但没有策略版本：策略换了而缓存不清，PDP 会继续按旧策略回答，
     * 而且旧键永远不会自然过期——元组坐标根本没动。
     */
    @Test
    void reloadFlushesTheCache() throws IOException {
        var counting = new CountingCache();

        try (var server = PdpServer.start(cached(counting))) {
            var body = """
                    {"subject":{"type":"user","id":"alice"},"object":{"type":"doc","id":"deep"},\
                    "relation":"view","at":1}""";
            var first = post(server.port(), "/v1/check", body, TOKEN);
            assertEquals(200, first.status(), "首次请求应当成功: " + first.body());
            assertEquals(1, counting.puts, "首次请求应当写入缓存");

            server.reload(FolderScenario.SCHEMA);
            assertEquals(1, counting.clears);

            post(server.port(), "/v1/check", body, TOKEN);
            assertEquals(2, counting.puts, "缓存已清，应当重新求值并写入");
        }
    }

    /** 远程下发 schema 默认拒绝：能改 schema 比能改元组的影响面更大。 */
    @Test
    void remoteSchemaIsDeniedByDefault() throws IOException {
        var response = post("/v1/schema", "{\"version\":1,\"types\":{}}", TOKEN);

        assertEquals(405, response.status(), response.body());
    }

    /** 显式装上解码器之后才能下发，而且要 WRITE 能力。 */
    @Test
    void remoteSchemaLoadsWhenDecoderIsWired() throws IOException {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var reloaded = new AtomicReference<byte[]>();
        var config = new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10,
                PdpServer.Config.DEFAULT_MAX_BODY, false,
                PdpServer.Extras.NONE.withSchemaDecoder(body -> {
                    reloaded.set(body);
                    return FolderScenario.SCHEMA;
                }));

        try (var server = PdpServer.start(config)) {
            var response = post(server.port(), "/v1/schema", "{\"version\":1}", TOKEN);

            assertEquals(200, response.status(), response.body());
            assertTrue(response.body().contains("\"reloaded\":true"), response.body());
            assertNotNull(reloaded.get());
            assertEquals(FolderScenario.SCHEMA, server.schema());
        }
    }

    /** 声明支持快照读但忽略坐标的测试替身：让缓存路径可测，不必拉起数据库。 */
    private PdpServer.Config cached(DecisionCache cache) {
        return cached(cache, AuditSink.NONE);
    }

    private PdpServer.Config cached(DecisionCache cache, AuditSink audit) {
        var backing = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var snapshotting = new SnapshotIgnoringTuples(backing);
        return new PdpServer.Config(0, FolderScenario.SCHEMA, snapshotting, attrs,
                new MemoryPlanExecutor(backing, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10,
                PdpServer.Config.DEFAULT_MAX_BODY, false,
                PdpServer.Extras.NONE.withCache(cache).withAudit(audit));
    }

    /**
     * 每次判定都要留痕，缓存命中的那次也算。
     *
     * <p>审计记的是"谁问到了什么答案"，缓存是服务端的实现细节。漏掉命中的那部分，同一串访问
     * 在审计里会时有时无——而这取决于缓存是否恰好过期，事后根本无法解释。
     */
    @Test
    void auditRecordsEveryDecisionIncludingCacheHits() throws IOException {
        var records = new java.util.concurrent.CopyOnWriteArrayList<String>();
        AuditSink audit = (subject, object, relation, allowed, at) ->
                records.add(object.id() + "#" + relation.name() + "=" + allowed + "@" + at.value());

        try (var server = PdpServer.start(cached(DecisionCache.bounded(8), audit))) {
            var body = """
                    {"subject":{"type":"user","id":"alice"},"object":{"type":"doc","id":"deep"},\
                    "relation":"view","at":1}""";
            post(server.port(), "/v1/check", body, TOKEN);
            post(server.port(), "/v1/check", body, TOKEN);

            assertEquals(List.of("deep#view=true@1", "deep#view=true@1"), records);
        }
    }

    private static final class CountingCache implements DecisionCache {

        private int puts;
        private int hits;
        private int clears;

        @Override
        public Boolean get(Key key) {
            var value = delegate.get(key);
            if (value != null) {
                hits++;
            }
            return value;
        }

        @Override
        public void put(Key key, boolean allowed) {
            puts++;
            delegate.put(key, allowed);
        }

        @Override
        public void clear() {
            clears++;
            delegate.clear();
        }

        private final DecisionCache delegate = DecisionCache.bounded(64);
    }

    /**
     * 声明 {@code snapshotRead} 但忽略坐标。
     *
     * <p>只用于验证缓存路径：内存适配器会拒绝非 HEAD 的读（这是对的），而缓存恰恰只在
     * 带具体坐标时生效，所以需要一个声明了该能力的替身。
     */
    private record SnapshotIgnoringTuples(MemoryTupleSource backing) implements TupleSource {

        @Override
        public java.util.Set<SubjectRef> subjects(facet.core.ir.ObjectRef obj, Rel rel) {
            return atHead(() -> backing.subjects(obj, rel));
        }

        @Override
        public java.util.stream.Stream<facet.core.ir.ObjectRef> objects(
                SubjectRef subject, Rel rel, facet.core.ir.ObjectType type) {
            return atHead(() -> backing.objects(subject, rel, type).toList()).stream();
        }

        @Override
        public Caps caps() {
            var inner = backing.caps();
            return new Caps(inner.reverseIndex(), true, inner.recursiveQuery(), inner.maxFanout());
        }

        /** 内存适配器拒绝非 HEAD 的读，所以把坐标剥掉再委托。 */
        private <T> T atHead(java.util.function.Supplier<T> body) {
            return Ctx.run(Ctx.Request.of(Ctx.current().principal()), body);
        }
    }

    /**
     * 观测挂点要真的装进请求上下文。
     *
     * <p>这一条防的是"端口存在但接不上"：{@code Metrics} 是 {@code Ctx.Request} 上的字段，
     * PDP 若不在建上下文时调 {@code withMetrics}，整套观测在 HTTP 部署里永远是 NOOP——
     * 而这不会让任何判定出错，只会让生产上一个数都看不到。
     */
    @Test
    void metricsAreInstalledIntoEveryRequest() throws IOException {
        var decisions = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var fanouts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var metrics = new Metrics() {

            @Override
            public void decision(Rel relation, boolean allowed, long elapsedNanos) {
                decisions.add(relation.name() + "=" + allowed);
            }

            @Override
            public void fanout(String operator, int width) {
                fanouts.add(operator + "=" + width);
            }

            @Override
            public void attributeBatch(facet.core.ir.AttrKey key, int size) {
            }
        };

        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var observed = new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10,
                PdpServer.Config.DEFAULT_MAX_BODY, false,
                PdpServer.Extras.NONE.withMetrics(metrics));

        try (var server = PdpServer.start(observed)) {
            post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN);

            assertEquals(List.of("view=true"), decisions);
            assertEquals(List.of("Through(parent)=1", "Through(parent)=1"), fanouts);
        }
    }

    /**
     * 可重试的存储故障给 503，并带上 {@code Retry-After}。
     *
     * <p>500 的语义是"服务端有 bug"，网关和客户端都不会重试它；而连接断开、死锁、语句超时
     * 恰恰重试一次就好。两者混成同一个码，调用方只能在"全都重试"和"全都不重试"之间选。
     */
    @Test
    void retryableStorageFailureIsServiceUnavailable() throws IOException {
        try (var server = PdpServer.start(failing(true))) {
            var response = post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN);

            assertEquals(503, response.status(), response.body());
            assertTrue(response.body().contains("storage_unavailable"), response.body());
        }
    }

    /** 不可重试的存储故障仍然是 500：让客户端去重试一个永远不会变的结果是浪费两边的资源。 */
    @Test
    void permanentStorageFailureStaysInternalError() throws IOException {
        try (var server = PdpServer.start(failing(false))) {
            var response = post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN);

            assertEquals(500, response.status(), response.body());
            assertTrue(response.body().contains("internal_error"), response.body());
        }
    }

    private PdpServer.Config failing(boolean retryable) {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        return new PdpServer.Config(0, FolderScenario.SCHEMA,
                new BrokenTuples(tuples.caps(), retryable), attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10, false);
    }

    /** 每次读取都报存储故障的替身：真实的连接中断在测试里没法稳定制造。 */
    private record BrokenTuples(TupleSource.Caps caps, boolean retryable) implements TupleSource {

        @Override
        public java.util.Set<SubjectRef> subjects(facet.core.ir.ObjectRef obj, Rel rel) {
            throw new facet.core.spi.StorageException("连接中断", null, retryable);
        }

        @Override
        public java.util.stream.Stream<facet.core.ir.ObjectRef> objects(
                SubjectRef subject, Rel rel, facet.core.ir.ObjectType type) {
            throw new facet.core.spi.StorageException("连接中断", null, retryable);
        }
    }

    /** 读凭据不能拿来写：能读的客户端拿到判定结果，能写的客户端能改写授权数据本身。 */
    @Test
    void writeRequiresItsOwnScope() throws IOException {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var readOnlyToken = new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs),
                (writes, deletes) -> new Revision(7),
                (authorization, scope) -> TOKEN.equals(authorization)
                        && scope == Authenticator.Scope.READ,
                10, false);

        try (var server = PdpServer.start(readOnlyToken)) {
            assertEquals(200, post(server.port(), "/v1/check", checkBody("alice", "deep"), TOKEN).status());
            assertEquals(401, post(server.port(), "/v1/relationships",
                    """
                    {"writes":[{"object":{"type":"doc","id":"n"},"relation":"viewer",\
                    "subject":{"type":"user","id":"d"}}]}""", TOKEN).status());
        }
    }

    /** 读端点能把整套授权关系导出去，删端点一次调用就能清空整个库——两者都必须显式打开。 */
    @Test
    void relationshipAdminIsDeniedByDefault() throws IOException {
        var readResponse = post("/v1/relationships/read",
                "{\"filter\":{}}", TOKEN);
        assertEquals(405, readResponse.status(), readResponse.body());

        var deleteResponse = post("/v1/relationships/delete",
                "{\"filter\":{}}", TOKEN);
        assertEquals(405, deleteResponse.status(), deleteResponse.body());
    }

    /** 显式接入运维端口后，按对象筛选能读回对应的元组。 */
    @Test
    void relationshipReadReturnsStoredTuples() throws IOException {
        try (var server = PdpServer.start(
                admin(2, (authorization, scope) -> TOKEN.equals(authorization)))) {
            var response = post(server.port(), "/v1/relationships/read",
                    "{\"filter\":{\"object\":{\"type\":\"doc\",\"id\":\"readme\"}}}", TOKEN);

            assertEquals(200, response.status(), response.body());
            // 只断言片段而不是整个响应：六列升序意味着 banned 在 editor 之前，
            // 但这条用例要看的是"筛选条件真的把 doc:readme 的元组取回来了"
            assertTrue(response.body().contains("\"relation\":\"banned\""), response.body());
            assertTrue(response.body().contains("\"relation\":\"editor\""), response.body());
            assertTrue(response.body().contains("{\"type\":\"user\",\"id\":\"alice\"}"),
                    response.body());
        }
    }

    /** 删端点走 WRITE 能力：只有 READ 权限的凭据能读元组，但不能按条件撤销。 */
    @Test
    void relationshipDeleteRequiresWriteScope() throws IOException {
        var readOnlyToken = admin(10, (authorization, scope) -> TOKEN.equals(authorization)
                && scope == Authenticator.Scope.READ);

        try (var server = PdpServer.start(readOnlyToken)) {
            assertEquals(200, post(server.port(), "/v1/relationships/read",
                    "{\"filter\":{}}", TOKEN).status());
            assertEquals(401, post(server.port(), "/v1/relationships/delete",
                    "{\"filter\":{}}", TOKEN).status());
        }
    }

    /** 客户端要 1000 条也只给服务端的一页上限，和反查同一个规矩；满页时给出游标。 */
    @Test
    void relationshipReadIsPagedByServer() throws IOException {
        try (var server = PdpServer.start(
                admin(2, (authorization, scope) -> TOKEN.equals(authorization)))) {
            var response = post(server.port(), "/v1/relationships/read",
                    "{\"filter\":{},\"limit\":1000}", TOKEN);

            assertEquals(200, response.status(), response.body());
            // 游标本身也是一条元组，所以只数 tuples 数组那一段
            var page = response.body().substring(0, response.body().indexOf("\"nextCursor\""));
            assertEquals(2, countOccurrences(page, "\"relation\":"), response.body());
            assertFalse(response.body().contains("\"nextCursor\":null"), response.body());
        }
    }

    /**
     * 装上运维端口的服务器。
     *
     * <p>读接到内存适配器上，撤销一律抛 {@code UnsupportedOperationException}——那不是偷懒，
     * 内存适配器只有一个版本，真的没有撤销能力。
     */
    private PdpServer.Config admin(int maxPage, Authenticator authenticator) {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var port = new RelationshipAdmin() {

            @Override
            public List<Tuple> read(TupleFilter filter, Tuple after, int limit) {
                return tuples.read(filter, after, limit);
            }

            @Override
            public Revision deleteWhere(TupleFilter filter) {
                throw new UnsupportedOperationException("内存适配器没有撤销能力");
            }
        };
        return new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                authenticator, maxPage, PdpServer.Config.DEFAULT_MAX_BODY, false,
                PdpServer.Extras.NONE.withAdmin(port));
    }

    /** 畸形 JSON 必须拿到 400，而不是空响应——Jackson 的异常是 IOException 子类，很容易漏接。 */
    @Test
    void malformedJsonIsClientError() throws IOException {
        var response = post("/v1/check", "{not json", TOKEN);

        assertEquals(400, response.status());
        assertTrue(response.body().contains("bad_request"), response.body());
    }

    /** 缺必填字段同样是 400：Jackson 会填 null，不在线格式里挡住就会变成 500。 */
    @Test
    void missingRequiredFieldIsClientError() throws IOException {
        var response = post("/v1/check", """
                {"object":{"type":"doc","id":"deep"},"relation":"view"}""", TOKEN);

        assertEquals(400, response.status());
        assertTrue(response.body().contains("subject"), response.body());
    }

    /** 请求体有硬上限：readAllBytes 不限长度，一个大 body 就能耗尽堆。 */
    @Test
    void oversizedBodyIsRejected() throws IOException {
        var tuples = new MemoryTupleSource().write(FolderScenario.TUPLES);
        var attrs = new MemoryAttrSource();
        var tiny = new PdpServer.Config(0, FolderScenario.SCHEMA, tuples, attrs,
                new MemoryPlanExecutor(tuples, attrs), RelationshipWriter.READ_ONLY,
                (authorization, scope) -> TOKEN.equals(authorization), 10, 64, false);

        try (var server = PdpServer.start(tiny)) {
            var padding = "x".repeat(200);
            var response = post(server.port(), "/v1/check",
                    """
                    {"subject":{"type":"user","id":"%s"},"object":{"type":"doc","id":"deep"},\
                    "relation":"view"}""".formatted(padding), TOKEN);

            assertEquals(413, response.status(), response.body());
        }
    }

    // ---- 极简 HTTP 客户端。用 java.base 里的 HttpURLConnection，省掉 java.net.http 的模块依赖 ----

    private record Response(int status, String body) {}

    private static String checkBody(String who, String doc) {
        return """
                {"subject":{"type":"user","id":"%s"},"object":{"type":"doc","id":"%s"},"relation":"view"}"""
                .formatted(who, doc);
    }

    private Response post(String path, String body, String token) throws IOException {
        return post(pdp.port(), path, body, token);
    }

    private static Response post(int port, String path, String body, String token) throws IOException {
        var connection = open(port, path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        // 不复用连接：每个用例都在新的临时端口上起服务器，JDK 的 KeepAliveCache 按
        // host:port 缓存 socket，端口被系统回收再分配之后会把旧连接交给新服务器，
        // 症状是某个用例偶发拿到与断言无关的状态码
        connection.setRequestProperty("Connection", "close");
        if (token != null) {
            connection.setRequestProperty("Authorization", token);
        }
        try (var out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        try (stream) {
            return new Response(status, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        return open(pdp.port(), path);
    }

    private static HttpURLConnection open(int port, String path) throws IOException {
        var url = URI.create("http://localhost:" + port + path).toURL();
        return (HttpURLConnection) url.openConnection();
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}

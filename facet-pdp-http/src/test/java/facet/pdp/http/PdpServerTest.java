package facet.pdp.http;

import facet.core.eval.Ctx;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
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
        assertEquals("""
                {"subjects":[{"type":"user","id":"alice"},{"type":"user","id":"carol"}]}""",
                response.body());
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

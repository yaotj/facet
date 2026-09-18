package facet.pdp.http;

import facet.core.ir.Revision;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void readOnlyDeploymentRefusesWrites() throws IOException {
        var response = post("/v1/relationships",
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

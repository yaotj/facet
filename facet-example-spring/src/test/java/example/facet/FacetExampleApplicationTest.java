package example.facet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实接入集成测试：启动真实 HTTP 服务（随机端口），用 RestClient 走完整请求语义。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code @CheckAllowed} 声明式方法安全 —— alice 放行、mallory 403；</li>
 *   <li>{@link FacetTemplate#lookup} 反查 —— alice 能看到 readme（经组→文件夹继承）；</li>
 *   <li>{@link FacetTemplate#whoCan} 展开 —— readme 的 viewer 含 alice / bob。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FacetExampleApplicationTest {

    private static final ParameterizedTypeReference<List<String>> STRING_LIST =
            new ParameterizedTypeReference<>() {};

    @LocalServerPort
    int port;

    private RestClient client(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User", user)
                .build();
    }

    @Test
    void aliceCanReadReadme() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = client("alice").get().uri("/docs/readme")
                .retrieve().body(Map.class);
        assertEquals("readme", body.get("id"));
        assertThat(body.get("content")).isNotNull();
    }

    @Test
    void malloryCannotReadReadme() {
        var response = client("mallory").get().uri("/docs/readme")
                .exchange((req, res) -> Map.of(
                        "status", res.getStatusCode().value(),
                        "body", res.bodyTo(Map.class)));
        assertEquals(403, response.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.get("body");
        assertEquals("forbidden", body.get("error"));
    }

    @Test
    void aliceCannotReadSecret() {
        var response = client("alice").get().uri("/docs/secret")
                .exchange((req, res) -> Map.of(
                        "status", res.getStatusCode().value(),
                        "body", res.bodyTo(Map.class)));
        assertEquals(403, response.get("status"));
    }

    @Test
    void aliceCanListVisibleDocs() {
        List<String> docs = client("alice").get().uri("/docs")
                .retrieve().body(STRING_LIST);
        assertThat(docs).containsExactly("readme");
    }

    @Test
    void mallorySeesEmptyList() {
        List<String> docs = client("mallory").get().uri("/docs")
                .retrieve().body(STRING_LIST);
        assertThat(docs).isEmpty();
    }

    @Test
    void readmeViewersIncludeAliceAndBob() {
        List<String> viewers = client("alice").get().uri("/docs/readme/viewers")
                .retrieve().body(STRING_LIST);
        assertThat(viewers).containsExactly("user:alice", "user:bob");
    }
}
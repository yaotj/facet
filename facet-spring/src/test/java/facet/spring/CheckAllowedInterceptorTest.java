package facet.spring;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.ir.json.SchemaJson;
import facet.core.schema.Schema;
import facet.spring.annotation.CheckAllowed;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @CheckAllowed 走 Spring AOP Advisor（MethodInterceptor）。CGLIB 代理具体类，拦截器从具体方法
 * 上取注解，对方法实参求 SpEL 得到 object（subject 可选从 SubjectResolver 取）。
 */
class CheckAllowedInterceptorTest {

    @Test
    void allowsWhenGrantedAndDeniesWhenNot() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("facet.method-security.enabled", "true")));
            ctx.register(MethodSecurityConfig.class, FacetAutoConfiguration.class);
            ctx.refresh();

            var svc = ctx.getBean(DocService.class);
            // alice 能看 readme（元组存在）-> 放行
            assertDoesNotThrow(svc::readReadme);
            // alice 不能看 secret（无元组）-> 拒绝
            assertThrows(FacetAccessDeniedException.class, svc::readSecret);
            // 用 subject SpEL 指名 bob（无授权）-> 拒绝
            assertThrows(FacetAccessDeniedException.class, svc::readReadmeAsBob);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MethodSecurityConfig {

        @Bean
        Schema schema() throws IOException {
            try (InputStream in = new ClassPathResource("schema.json").getInputStream()) {
                return SchemaJson.decode(in.readAllBytes());
            }
        }

        @Bean
        MemoryTupleSource tuples() {
            var t = new MemoryTupleSource();
            t.write(new Tuple(
                    new ObjectRef(new ObjectType("doc"), "readme"),
                    new Rel("view"),
                    new SubjectRef.Principal(new ObjectType("user"), "alice")));
            return t;
        }

        @Bean
        MemoryAttrSource attrs() {
            return new MemoryAttrSource();
        }

        @Bean
        MemoryPlanExecutor executor(MemoryTupleSource tuples, MemoryAttrSource attrs) {
            return new MemoryPlanExecutor(tuples, attrs);
        }

        @Bean
        SubjectResolver subjectResolver() {
            return () -> new SubjectRef.Principal(new ObjectType("user"), "alice");
        }

        @Bean
        DocService docService() {
            return new DocService();
        }
    }

    static class DocService {

        @CheckAllowed(relation = "view", object = "'doc:readme'")
        void readReadme() {
        }

        @CheckAllowed(relation = "view", object = "'doc:secret'")
        void readSecret() {
        }

        @CheckAllowed(relation = "view", object = "'doc:readme'", subject = "'user:bob'")
        void readReadmeAsBob() {
        }
    }
}

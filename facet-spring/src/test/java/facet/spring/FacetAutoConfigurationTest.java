package facet.spring;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.ir.json.SchemaJson;
import facet.core.schema.Schema;
import facet.sdk.Facet;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动装配的两个主路径：显式 Schema Bean，以及通过 {@code facet.schema-location} 从 JSON 加载。
 * 两者都要求调用方提供 TupleSource / AttrSource（这里用内存适配器）。
 */
class FacetAutoConfigurationTest {

    @Test
    void wiresFacetAndTemplateBeansAndChecksPass() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(WithSchemaBean.class, FacetAutoConfiguration.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(Facet.class));
            var template = ctx.getBean(FacetTemplate.class);

            var alice = new SubjectRef.Principal(new ObjectType("user"), "alice");
            var readme = new ObjectRef(new ObjectType("doc"), "readme");
            assertTrue(template.isAllowed(alice, readme, new Rel("view")));
            assertFalse(template.isAllowed(
                    new SubjectRef.Principal(new ObjectType("user"), "mallory"), readme, new Rel("view")));
        }
    }

    @Test
    void loadsSchemaFromPropertyLocation() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("facet.schema-location", "classpath:schema.json")));
            ctx.register(NoSchemaBean.class, FacetAutoConfiguration.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(Schema.class));
            var template = ctx.getBean(FacetTemplate.class);
            var alice = new SubjectRef.Principal(new ObjectType("user"), "alice");
            var readme = new ObjectRef(new ObjectType("doc"), "readme");
            assertTrue(template.isAllowed(alice, readme, new Rel("view")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithSchemaBean {

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
    }

    @Configuration(proxyBeanMethods = false)
    static class NoSchemaBean {

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
    }
}

package facet.spring;

import facet.ir.json.SchemaJson;
import facet.core.schema.Schema;
import facet.core.spi.AttrSource;
import facet.core.spi.DecisionCache;
import facet.core.spi.Metrics;
import facet.core.spi.PlanExecutor;
import facet.core.spi.RevisionSource;
import facet.core.spi.TupleSource;
import facet.sdk.Facet;
import facet.spring.annotation.CheckAllowed;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Facet 的 Spring Boot 自动装配。
 *
 * <p>把 {@code Facet} 门面装成 Bean：存储适配器（{@code TupleSource}/{@code AttrSource}）与
 * {@code Schema} 由调用方提供成 Bean；{@code PlanExecutor}/{@code Metrics}/{@code RevisionSource}
 * 可选。另提供 {@link FacetTemplate} 类型安全客户端与可选的 {@link CheckAllowed} 方法安全。
 *
 * <p>方法安全走 Spring AOP 的 {@code MethodInterceptor} + {@code Advisor}，不依赖 AspectJ——
 * 只用 {@code spring-aop}，和 Spring 自己的 {@code @PreAuthorize} 是同一套落地方式。
 */
@AutoConfiguration
@EnableConfigurationProperties(FacetProperties.class)
public class FacetAutoConfiguration {

    /**
     * Schema：若配了 {@code facet.schema-location} 且容器内尚无 Schema Bean，从 JSON 加载。
     * 留空则交回给调用方——没有 Schema 时本 Bean 不创建（返回 null 即无 Bean）。
     */
    @Bean
    @ConditionalOnMissingBean
    public Schema facetSchema(FacetProperties props, ResourceLoader loader) throws IOException {
        var loc = props.getSchemaLocation();
        if (loc == null || loc.isBlank()) {
            return null;
        }
        Resource resource = loader.getResource(loc);
        if (!resource.exists()) {
            throw new IllegalStateException("facet.schema-location 指向的资源不存在: " + loc);
        }
        try (InputStream in = resource.getInputStream()) {
            return SchemaJson.decode(in.readAllBytes());
        }
    }

    /**
     * 判定门面。要求 Schema + TupleSource + AttrSource 三个 Bean 已就位；缺任一则本 Bean 不创建，
     * 让调用方拿到的是"没有 Facet"，而不是一堆含糊的 NPE。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({Schema.class, TupleSource.class, AttrSource.class})
    public Facet facet(FacetProperties props,
                       Schema schema,
                       TupleSource tuples,
                       AttrSource attrs,
                       ObjectProvider<PlanExecutor> executor,
                       ObjectProvider<Metrics> metrics,
                       ObjectProvider<RevisionSource> revisions) {
        var builder = Facet.builder(schema).tuples(tuples).attrs(attrs);
        executor.ifAvailable(builder::executor);
        metrics.ifAvailable(builder::metrics);
        if (props.getMaxNodes() > 0) {
            builder.maxNodes(props.getMaxNodes());
        }
        if (props.getMaxDepth() > 0) {
            builder.maxDepth(props.getMaxDepth());
        }
        if (props.getDeadline() != null && !props.getDeadline().isZero()) {
            builder.deadline(props.getDeadline());
        }
        var cache = props.getCache();
        if (cache.isEnabled()) {
            builder.withCache(DecisionCache.bounded(cache.getMaximumSize()));
            var staleness = cache.getStaleness();
            if (staleness != null && !staleness.isZero()) {
                var source = revisions.getIfAvailable();
                if (source == null) {
                    throw new IllegalStateException(
                            "开了有界陈旧窗口（facet.cache.staleness > 0），但容器里没有 RevisionSource Bean；"
                                    + "钉住坐标读需要它来给出略旧但新鲜的坐标");
                }
                builder.withStaleness(source, staleness);
            }
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Facet.class)
    public FacetTemplate facetTemplate(Facet facet) {
        return new FacetTemplate(facet);
    }

    /**
     * 方法安全开启时确保自动代理创建器在场。Spring Boot 自带的 AopAutoConfiguration 通常已注册，
     * 这里兜底（重复注册是幂等的）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "facet.method-security", name = "enabled", havingValue = "true")
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class MethodSecurityConfig {
    }

    /** 把 @CheckAllowed 拦截器注册成 Advisor；仅方法安全开启、且 Facet 在场时生效。 */
    @Bean
    @ConditionalOnProperty(prefix = "facet.method-security", name = "enabled", havingValue = "true")
    @ConditionalOnBean(Facet.class)
    public org.springframework.aop.Advisor checkAllowedAdvisor(
            Facet facet, ObjectProvider<SubjectResolver> resolver) {
        var pointcut = AnnotationMatchingPointcut
                .forMethodAnnotation(CheckAllowed.class);
        return new org.springframework.aop.support.DefaultPointcutAdvisor(
                pointcut, new CheckAllowedInterceptor(facet, resolver));
    }
}

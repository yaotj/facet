package example.facet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 真实接入示例的启动类。
 *
 * <p>自动配置会加载 {@code facet-spring} 的 {@code FacetAutoConfiguration}：
 * Schema 从 {@code classpath:schema.json} 加载，TupleSource/AttrSource 由
 * {@link FacetExampleConfig} 提供，{@code facet.method-security.enabled=true} 开启
 * {@code @CheckAllowed} 方法安全。
 */
@SpringBootApplication
public class FacetExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacetExampleApplication.class, args);
    }
}
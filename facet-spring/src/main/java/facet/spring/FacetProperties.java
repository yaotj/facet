package facet.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Facet 的 Spring Boot 配置绑定（前缀 {@code facet}）。
 *
 * <p>只覆盖"调优旋钮 + 缓存 + 方法安全开关 + schema 来源"四类。存储适配器
 * （{@code TupleSource}/{@code AttrSource}/{@code PlanExecutor}）是应用特有的，不由属性接管，
 * 而是要求调用方提供成 Bean——强行用属性描述一个存储适配器的装配既不自然也不可移植。
 */
@ConfigurationProperties(prefix = "facet")
public class FacetProperties {

    /**
     * schema 的 JSON 来源：{@code classpath:}、{@code file:} 或 URL，指向一份
     * facet-ir-json 线格式（{@code version:1}）。留空则要求调用方提供 {@code Schema} Bean。
     */
    private String schemaLocation = "";

    /** 单次判定的工作预算（求值节点总数）。0 = 用内核默认。 */
    private int maxNodes = 0;

    /** 递归深度上限。0 = 用内核默认。 */
    private int maxDepth = 0;

    /** 墙钟期限；默认无期限（{@link Duration#ZERO}）。 */
    private Duration deadline = Duration.ZERO;

    private final Cache cache = new Cache();

    private final MethodSecurity methodSecurity = new MethodSecurity();

    public static class Cache {

        /** 是否启用判定缓存。默认关——缓存是可选加速，不是默认行为。 */
        private boolean enabled = false;

        /** 有界 LRU 容量。默认 1 万。 */
        private int maximumSize = 10_000;

        /**
         * 有界陈旧窗口。大于 0 时必须同时提供 {@code RevisionSource} Bean（读 HEAD 才能钉到
         * 略旧坐标、从而进缓存）；不配则装配期抛错。
         */
        private Duration staleness = Duration.ZERO;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean e) {
            this.enabled = e;
        }

        public int getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(int s) {
            this.maximumSize = s;
        }

        public Duration getStaleness() {
            return staleness;
        }

        public void setStaleness(Duration d) {
            this.staleness = d;
        }
    }

    public static class MethodSecurity {

        /** 是否启用 {@code @CheckAllowed} 方法安全。默认关。 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean e) {
            this.enabled = e;
        }
    }

    public String getSchemaLocation() {
        return schemaLocation;
    }

    public void setSchemaLocation(String s) {
        this.schemaLocation = s;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int n) {
        this.maxNodes = n;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int d) {
        this.maxDepth = d;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration d) {
        this.deadline = d;
    }

    public Cache getCache() {
        return cache;
    }

    public MethodSecurity getMethodSecurity() {
        return methodSecurity;
    }
}

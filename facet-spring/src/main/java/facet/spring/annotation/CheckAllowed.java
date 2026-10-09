package facet.spring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级授权：标注在 Spring Bean 的方法上，调用前先跑一次 Facet 判定，拒绝则抛
 * {@link facet.spring.FacetAccessDeniedException}。
 *
 * <p>{@code object}/{@code subject} 是 SpEL，对方法实参求值（参数以 {@code #a0, #a1, ...} 暴露，
 * 若编译带 {@code -parameters} 也可用形参名）；结果须为 {@code ObjectRef} 或 {@code "type:id"} 字符串。
 * {@code subject} 留空则从 {@link facet.spring.SubjectResolver} Bean 取当前主体。
 *
 * <p>落在具体类的方法上（而非接口方法）——自动代理用 CGLIB，取的是具体方法上的注解。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckAllowed {

    /** 关系名（字面量）。 */
    String relation();

    /** 对象，SpEL。可解析为 {@code ObjectRef}、{@code "type:id"} 字符串，或直接是形参。 */
    String object();

    /** 主体，SpEL。留空则用 {@link facet.spring.SubjectResolver} Bean。 */
    String subject() default "";
}

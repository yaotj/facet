package facet.spring;

import facet.core.ir.SubjectRef;

/**
 * 当前主体的来源。应用自己提供成 Bean（通常从 Spring Security 的 Authentication、
 * 或线程本地、或请求头里取）。{@code @CheckAllowed} 未显式给 {@code subject} 时用它。
 */
@FunctionalInterface
public interface SubjectResolver {

    /** 取当前请求上下文里的主体（如 {@code user:alice}）。 */
    SubjectRef resolve();
}

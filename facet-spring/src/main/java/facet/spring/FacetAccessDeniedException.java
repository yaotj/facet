package facet.spring;

import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;

/**
 * {@code @CheckAllowed} 判定拒绝时抛出。不依赖 Spring Security，避免把安全抽象焊进 starter——
 * 应用想接 Spring Security 的 {@code AccessDeniedException} 时，在自己的 {@code @ControllerAdvice}
 * 里捕获它再转成对应的 HTTP 状态即可。
 */
public class FacetAccessDeniedException extends RuntimeException {

    public FacetAccessDeniedException(SubjectRef subject, ObjectRef object, Rel relation) {
        super("Facet 判定拒绝：" + subject + " 不能对 " + object + " 做 " + relation);
    }
}

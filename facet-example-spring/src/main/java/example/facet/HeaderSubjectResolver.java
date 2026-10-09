package example.facet;

import facet.core.ir.ObjectType;
import facet.core.ir.SubjectRef;
import facet.spring.SubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 模拟登录：从 {@code X-User} 请求头取当前用户，返回 {@code user:<name>}。
 *
 * <p>真实应用通常从 Spring Security 的 {@code Authentication} 取；这里用请求头演示
 * {@code SubjectResolver} 的接入点，让 {@code @CheckAllowed} 在「未显式给 subject」时
 * 也能拿到当前主体。
 */
@Component
public class HeaderSubjectResolver implements SubjectResolver {

    @Override
    public SubjectRef resolve() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        String user = request.getHeader("X-User");
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("缺少 X-User 请求头");
        }
        return new SubjectRef.Principal(new ObjectType("user"), user);
    }
}
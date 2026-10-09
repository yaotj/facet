package facet.spring;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.runtime.Decision;
import facet.sdk.Facet;
import facet.spring.annotation.CheckAllowed;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * {@code @CheckAllowed} 的拦截器：在方法执行前求值 subject/object/relation 并跑判定，拒绝即抛异常。
 *
 * <p>走 Spring AOP 的 {@code MethodInterceptor} + Advisor，不依赖 AspectJ——只用 {@code spring-aop}。
 * 这与 Spring 自己的 {@code @PreAuthorize} 是同一套落地方式（方法拦截器 + 切点顾问）。
 */
public class CheckAllowedInterceptor implements MethodInterceptor {

    private final Facet facet;
    private final ObjectProvider<SubjectResolver> resolver;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    public CheckAllowedInterceptor(Facet facet, ObjectProvider<SubjectResolver> resolver) {
        this.facet = facet;
        this.resolver = resolver;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        var method = invocation.getMethod();
        var ann = method.getAnnotation(CheckAllowed.class);
        if (ann == null) {
            return invocation.proceed();
        }
        // 参数以 #a0, #a1, ... 暴露；带 -parameters 编译时（Spring Boot 默认）还能用形参名
        var ctx = new StandardEvaluationContext();
        var args = invocation.getArguments();
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("a" + i, args[i]);
        }
        var names = parameterNameDiscoverer.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                // 只有真实的形参名值得绑定；arg0/arg1 之类由 #aN 已经覆盖
                if (names[i] != null && !names[i].startsWith("arg")) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
        }
        var object = toObjectRef(eval(ann.object(), ctx));
        var subject = ann.subject().isBlank()
                ? resolveSubject()
                : toSubjectRef(eval(ann.subject(), ctx));
        var relation = new Rel(ann.relation());
        Decision decision = facet.check(subject, object, relation);
        if (!decision.allowed()) {
            throw new FacetAccessDeniedException(subject, object, relation);
        }
        return invocation.proceed();
    }

    private Object eval(String expression, EvaluationContext ctx) {
        return parser.parseExpression(expression).getValue(ctx);
    }

    private SubjectRef resolveSubject() {
        var r = resolver.getIfAvailable();
        if (r == null) {
            throw new IllegalStateException(
                    "@CheckAllowed 未给 subject 且容器里没有 SubjectResolver Bean："
                            + "无法获知当前主体。请提供 SubjectResolver，或在注解里用 SpEL 显式给 subject");
        }
        return r.resolve();
    }

    private static ObjectRef toObjectRef(Object value) {
        if (value instanceof ObjectRef ref) {
            return ref;
        }
        if (value instanceof String s) {
            return parseObjectRef(s);
        }
        throw new IllegalArgumentException(
                "@CheckAllowed 的 object 求值结果须为 ObjectRef 或 \"type:id\" 字符串，实际: "
                        + (value == null ? "null" : value.getClass().getName()));
    }

    private static SubjectRef toSubjectRef(Object value) {
        if (value instanceof SubjectRef ref) {
            return ref;
        }
        if (value instanceof String s) {
            return parseSubjectRef(s);
        }
        throw new IllegalArgumentException(
                "@CheckAllowed 的 subject 求值结果须为 SubjectRef 或 \"type:id\" 字符串，实际: "
                        + (value == null ? "null" : value.getClass().getName()));
    }

    private static ObjectRef parseObjectRef(String s) {
        var parts = split(s);
        return new ObjectRef(new ObjectType(parts[0]), parts[1]);
    }

    private static SubjectRef parseSubjectRef(String s) {
        var parts = split(s);
        return new SubjectRef.Principal(new ObjectType(parts[0]), parts[1]);
    }

    private static String[] split(String s) {
        int idx = s.indexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("\"type:id\" 形式应有冒号分隔：" + s);
        }
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }
}

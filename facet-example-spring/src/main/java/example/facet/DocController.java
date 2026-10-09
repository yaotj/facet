package example.facet;

import facet.core.eval.Expander;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.spring.FacetAccessDeniedException;
import facet.spring.FacetTemplate;
import facet.spring.annotation.CheckAllowed;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 授权场景的 REST 接口。
 *
 * <ul>
 *   <li>{@code GET /docs/{id}}：声明式方法安全（{@code @CheckAllowed}），拒绝返回 403；</li>
 *   <li>{@code GET /docs}：编程式反查（{@link FacetTemplate#lookup}），列出当前用户能看的文档；</li>
 *   <li>{@code GET /docs/{id}/viewers}：展开谁可看（{@link FacetTemplate#whoCan}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/docs")
public class DocController {

    private final FacetTemplate facet;

    public DocController(FacetTemplate facet) {
        this.facet = facet;
    }

    /** 读取文档内容。方法安全拦截器在进入方法体前判定；拒绝抛 FacetAccessDeniedException → 403。 */
    @GetMapping("/{id}")
    @CheckAllowed(relation = "view", object = "'doc:' + #id")
    public Map<String, String> read(@PathVariable String id) {
        return Map.of("id", id, "content", "这是文档 " + id + " 的内容");
    }

    /** 当前用户能看的文档列表（反查）。 */
    @GetMapping
    public List<String> list() {
        var subject = subjectFromHeader();
        var docs = facet.lookup(subject, new ObjectType("doc"), new Rel("view"), 10);
        return docs.stream().map(ObjectRef::id).toList();
    }

    /** 谁可以看这份文档（展开）。返回 "type:id" 形式的具体主体列表。 */
    @GetMapping("/{id}/viewers")
    public List<String> viewers(@PathVariable String id) {
        var object = new ObjectRef(new ObjectType("doc"), id);
        Expander.Subjects subjects = facet.whoCan(object, new Rel("view"), 10);
        return subjects.principals().stream()
                .map(p -> p.type().name() + ":" + p.id())
                .toList();
    }

    private SubjectRef subjectFromHeader() {
        // 复用 HeaderSubjectResolver 的逻辑（当前请求上下文里拿 X-User）
        return new SubjectRef.Principal(new ObjectType("user"), currentUser());
    }

    private String currentUser() {
        var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest().getHeader("X-User");
    }

    @ExceptionHandler(FacetAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> denied(FacetAccessDeniedException e) {
        return Map.of("error", "forbidden", "message", e.getMessage());
    }
}
package example.facet;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储装配：内存适配器 + 种子授权数据。
 *
 * <p>数据模型：
 * <ul>
 *   <li>{@code group:eng} 的成员是 {@code user:alice}、{@code user:bob}；</li>
 *   <li>{@code folder:eng} 的 viewer 是 {@code group:eng}；</li>
 *   <li>{@code doc:readme} 的 parent 是 {@code folder:eng}。</li>
 * </ul>
 * 于是 alice / bob 通过「组 → 文件夹 → 文档」的继承链能看 readme；mallory 不能。
 */
@Configuration(proxyBeanMethods = false)
public class FacetExampleConfig {

    public static final ObjectType USER = new ObjectType("user");
    public static final ObjectType GROUP = new ObjectType("group");
    public static final ObjectType FOLDER = new ObjectType("folder");
    public static final ObjectType DOC = new ObjectType("doc");

    public static final Rel MEMBER = new Rel("member");
    public static final Rel VIEWER = new Rel("viewer");
    public static final Rel PARENT = new Rel("parent");
    public static final Rel VIEW = new Rel("view");

    public static final ObjectRef ALICE = new ObjectRef(USER, "alice");
    public static final ObjectRef BOB = new ObjectRef(USER, "bob");
    public static final ObjectRef MALLORY = new ObjectRef(USER, "mallory");

    public static final ObjectRef GROUP_ENG = new ObjectRef(GROUP, "eng");
    public static final ObjectRef FOLDER_ENG = new ObjectRef(FOLDER, "eng");
    public static final ObjectRef DOC_README = new ObjectRef(DOC, "readme");
    public static final ObjectRef DOC_SECRET = new ObjectRef(DOC, "secret");

    /** 当前主体的 SubjectRef 形态（接口要用）。 */
    public static SubjectRef subject(String user) {
        return new SubjectRef.Principal(USER, user);
    }

    @Bean
    MemoryTupleSource tuples() {
        var source = new MemoryTupleSource();
        source.write(
                Tuple.of(GROUP_ENG, MEMBER, ALICE),
                Tuple.of(GROUP_ENG, MEMBER, BOB),
                // folder:eng 的 viewer 是 group:eng 的成员集合（userset），不是 group:eng 这个具体主体
                Tuple.of(FOLDER_ENG, VIEWER, GROUP_ENG, MEMBER),
                Tuple.of(DOC_README, PARENT, FOLDER_ENG));
        return source;
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
package facet.testkit;

import facet.core.schema.Schema;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;

import java.util.List;
import java.util.Map;

/**
 * 标准场景：folder → folder → doc 的多层继承，外加 group userset、deny、条件三种形态。
 *
 * <p>放在 testkit 而不是某个适配器的测试里，是为了让<strong>同一份数据在不同存储上跑同一套
 * 断言</strong>成为可能。跨适配器一致性是这套架构的核心保证：check 与反查两条路要一致，
 * 内存与真实存储两个实现也要一致。各自维护一份 fixture 的话，不一致会被 fixture 的差异掩盖。
 */
public final class FolderScenario {

    /** 主体类型。场景里只有 principal 主体是这个类型。 */
    public static final ObjectType USER = new ObjectType("user");

    /** 用于覆盖 userset 授权：元组的主体侧是 {@code group:x#member} 而不是某个具体用户。 */
    public static final ObjectType GROUP = new ObjectType("group");

    /** 层级的中间节点，自身可再有 {@code parent}，用来把继承深度做到两层以上。 */
    public static final ObjectType FOLDER = new ObjectType("folder");

    /** 层级的叶子，deny 与条件两种形态都挂在它上面。 */
    public static final ObjectType DOC = new ObjectType("doc");

    /** 纯存储关系，直接授权。 */
    public static final Rel VIEWER = new Rel("viewer");

    /** group 的成员关系，作为 userset 授权的被解引用目标。 */
    public static final Rel MEMBER = new Rel("member");

    /** 层级边，同时是 {@code Through} 的 hop，因此声明了目标类型。 */
    public static final Rel PARENT = new Rel("parent");

    /** {@code edit} 的基础侧。 */
    public static final Rel EDITOR = new Rel("editor");

    /** {@code edit} 的否定侧，用来验证 deny 的单调性。 */
    public static final Rel BANNED = new Rel("banned");

    /** 计算关系：直接 viewer 或父级的 view，是递归形状的主角，声明为可反查。 */
    public static final Rel VIEW = new Rel("view");

    /** 计算关系：editor 减去 banned。 */
    public static final Rel EDIT = new Rel("edit");

    /** 计算关系：view 再加 MFA 条件，用来验证条件路径仍然可反查。 */
    public static final Rel VIEW_MFA = new Rel("view_mfa");

    /** CONTEXT 等级的开关属性。停在 CONTEXT 才不会破坏 {@link #VIEW_MFA} 的可反查性。 */
    public static final AttrKey MFA = AttrKey.bool("mfa", AttrKey.Tier.CONTEXT);

    /** ReBAC：本级 viewer，或父级的 view——{@code Ref} 让层级深度由数据决定。 */
    public static final Perm INHERITED_VIEW = new Perm.AnyOf(List.of(
            new Perm.Direct(VIEWER),
            new Perm.Through(PARENT, new Perm.Ref(VIEW))));

    /** 场景 schema。四种形态各一条：多层继承、userset、deny、条件——少一条就少一类跨适配器偏差。 */
    public static final Schema SCHEMA = new Schema(Map.of(
            GROUP, new Schema.TypeDef(Map.of(MEMBER, Schema.tuples(MEMBER))),
            FOLDER, new Schema.TypeDef(Map.of(
                    VIEWER, Schema.tuples(VIEWER),
                    PARENT, Schema.tuples(PARENT, FOLDER),
                    VIEW, Schema.computed(INHERITED_VIEW, true))),
            DOC, new Schema.TypeDef(Map.of(
                    VIEWER, Schema.tuples(VIEWER),
                    EDITOR, Schema.tuples(EDITOR),
                    BANNED, Schema.tuples(BANNED),
                    PARENT, Schema.tuples(PARENT, FOLDER),
                    VIEW, Schema.computed(INHERITED_VIEW, true),
                    // deny 单调：banned 一旦命中，上层无法恢复
                    EDIT, Schema.computed(
                            new Perm.Minus(new Perm.Direct(EDITOR), new Perm.Direct(BANNED)), true),
                    // ABAC：唯一挂载点是 Guarded，条件停在 CONTEXT 等级所以仍可反查
                    VIEW_MFA, Schema.computed(new Perm.Guarded(new Perm.Ref(VIEW),
                            new Cond.Cmp(Cond.Op.EQ, new Cond.Term.Attr(MFA),
                                    new Cond.Term.Lit("true"))), true)))));

    /**
     * 场景数据。每条元组都在覆盖一个具体形态，删改任何一条都会让某类偏差失去覆盖：
     * {@code carol} 只经 group 拿到权限，{@code deep} 要跳两层 folder，{@code alice} 同时是
     * {@code readme} 的 editor 和 banned。
     */
    public static final List<Tuple> TUPLES = List.of(
            Tuple.of(folder("eng"), VIEWER, user("alice")),
            Tuple.of(folder("eng"), VIEWER, group("eng"), MEMBER),
            Tuple.of(group("eng"), MEMBER, user("carol")),
            Tuple.of(folder("team"), PARENT, folder("eng")),
            Tuple.of(doc("readme"), PARENT, folder("eng")),
            Tuple.of(doc("spec"), PARENT, folder("eng")),
            Tuple.of(doc("deep"), PARENT, folder("team")),
            Tuple.of(doc("private"), VIEWER, user("bob")),
            Tuple.of(doc("readme"), EDITOR, user("alice")),
            Tuple.of(doc("readme"), BANNED, user("alice")));

    private FolderScenario() {
    }

    /** {@code doc:id}。 */
    public static ObjectRef doc(String id) {
        return new ObjectRef(DOC, id);
    }

    /** {@code folder:id}。 */
    public static ObjectRef folder(String id) {
        return new ObjectRef(FOLDER, id);
    }

    /** {@code group:id}。作为对象出现时是 userset 的宿主，配合 {@link #MEMBER} 使用。 */
    public static ObjectRef group(String id) {
        return new ObjectRef(GROUP, id);
    }

    /** {@code user:id}。用户出现在元组主体侧时用 {@link #principal}，这个方法给的是对象形态。 */
    public static ObjectRef user(String id) {
        return new ObjectRef(USER, id);
    }

    /** 判定请求里的主体。返回具体类型而不是 {@code SubjectRef}，省掉调用方的强转。 */
    public static SubjectRef.Principal principal(String id) {
        return new SubjectRef.Principal(USER, id);
    }
}

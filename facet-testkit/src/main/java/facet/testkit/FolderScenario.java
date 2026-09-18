package facet.testkit;

import facet.core.eval.Schema;
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

    public static final ObjectType USER = new ObjectType("user");
    public static final ObjectType GROUP = new ObjectType("group");
    public static final ObjectType FOLDER = new ObjectType("folder");
    public static final ObjectType DOC = new ObjectType("doc");

    public static final Rel VIEWER = new Rel("viewer");
    public static final Rel MEMBER = new Rel("member");
    public static final Rel PARENT = new Rel("parent");
    public static final Rel EDITOR = new Rel("editor");
    public static final Rel BANNED = new Rel("banned");
    public static final Rel VIEW = new Rel("view");
    public static final Rel EDIT = new Rel("edit");
    public static final Rel VIEW_MFA = new Rel("view_mfa");

    public static final AttrKey MFA = AttrKey.bool("mfa", AttrKey.Tier.CONTEXT);

    /** ReBAC：本级 viewer，或父级的 view——{@code Ref} 让层级深度由数据决定。 */
    public static final Perm INHERITED_VIEW = new Perm.AnyOf(List.of(
            new Perm.Direct(VIEWER),
            new Perm.Through(PARENT, new Perm.Ref(VIEW))));

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

    public static ObjectRef doc(String id) {
        return new ObjectRef(DOC, id);
    }

    public static ObjectRef folder(String id) {
        return new ObjectRef(FOLDER, id);
    }

    public static ObjectRef group(String id) {
        return new ObjectRef(GROUP, id);
    }

    public static ObjectRef user(String id) {
        return new ObjectRef(USER, id);
    }

    public static SubjectRef.Principal principal(String id) {
        return new SubjectRef.Principal(USER, id);
    }
}

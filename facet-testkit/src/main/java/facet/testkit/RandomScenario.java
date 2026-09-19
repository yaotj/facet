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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 受约束的随机场景生成器。
 *
 * <p>固定场景盖不住组合爆炸，而这个项目的核心保证恰恰是"两个实现、两条路径对同一份数据
 * 得出同一个答案"——这种保证只能靠差分测试验证。已经找出来的几处分歧（{@code BigDecimal}
 * 比精度、不可变 Set 迭代顺序随机、排序规则不对齐）全都是固定场景发现不了的类型。
 *
 * <p>随机是<strong>受约束</strong>的：类型与关系名固定，只有权限定义的组合与元组的连线随机。
 * 完全随机会大量生成被 {@code Validator} 拒绝或被 {@code Planner} 判为不支持形状的 schema，
 * 那样测试的就是生成器而不是内核。约束保证每次产出的 schema 都：
 * <ul>
 *   <li>顶层是 {@code AnyOf} 且含非递归基础项——反查才能分离出递归 CTE 的种子集；</li>
 *   <li>递归引用直接挂在 {@code Through} 之下——{@code Planner} 支持的唯一递归形状；</li>
 *   <li>否定只作用于不在递归环里的关系——分层否定，有唯一最小模型；</li>
 *   <li>条件只用 CONTEXT 属性——可反查路径上不允许 EXTERNAL。</li>
 * </ul>
 *
 * <p>一次生成覆盖全部七个 {@code Perm} 算子、全部八个 {@code Plan} 算子，
 * 以及三种主体形态（具体主体、userset、通配）。
 *
 * <p><strong>失败时打印种子</strong>：种子加上这个生成器就是完整的复现条件，
 * 不需要把失败数据序列化出来。
 */
public final class RandomScenario {

    public static final ObjectType USER = new ObjectType("user");
    public static final ObjectType GROUP = new ObjectType("group");
    public static final ObjectType FOLDER = new ObjectType("folder");
    public static final ObjectType DOC = new ObjectType("doc");

    public static final Rel VIEWER = new Rel("viewer");
    public static final Rel EDITOR = new Rel("editor");
    public static final Rel BANNED = new Rel("banned");
    public static final Rel MEMBER = new Rel("member");
    public static final Rel PARENT = new Rel("parent");
    public static final Rel VIEW = new Rel("view");

    public static final AttrKey MFA = AttrKey.bool("mfa", AttrKey.Tier.CONTEXT);

    private static final int USERS = 4;
    private static final int GROUPS = 2;
    private static final int FOLDERS = 5;
    private static final int DOCS = 8;

    /**
     * 一次生成的完整场景。
     *
     * @param subjects 待验证的主体：四个具体用户、一个 userset（覆盖间接授权），
     *                 以及一个没有任何元组的用户（覆盖仅靠通配放行）
     * @param context  请求上下文属性，条件求值要用
     */
    public record Generated(long seed,
                            Schema schema,
                            List<Tuple> tuples,
                            List<SubjectRef> subjects,
                            List<ObjectRef> docs,
                            Map<String, Object> context) {}

    private RandomScenario() {
    }

    public static Generated of(long seed) {
        var random = new Random(seed);
        var schema = schema(random);
        var docs = refs(DOC, DOCS);
        var folders = refs(FOLDER, FOLDERS);
        var groups = refs(GROUP, GROUPS);
        var users = refs(USER, USERS);

        var tuples = new ArrayList<Tuple>();
        // 组成员
        for (var group : groups) {
            for (var user : users) {
                if (random.nextInt(3) == 0) {
                    tuples.add(Tuple.of(group, MEMBER, user));
                }
            }
        }
        // folder 层级：只指向序号更小的 folder，因此无环。环有专门的用例覆盖，
        // 混进随机场景只会让失败原因难以归因。
        for (int i = 1; i < folders.size(); i++) {
            if (random.nextInt(3) > 0) {
                tuples.add(Tuple.of(folders.get(i), PARENT, folders.get(random.nextInt(i))));
            }
        }
        // doc 挂到随机 folder 下
        for (var doc : docs) {
            if (random.nextInt(4) > 0) {
                tuples.add(Tuple.of(doc, PARENT, folders.get(random.nextInt(folders.size()))));
            }
        }
        // 直接授权：主体可能是具体用户，也可能是 group#member 这种 userset
        for (var object : concat(folders, docs)) {
            for (var relation : List.of(VIEWER, EDITOR, BANNED)) {
                if (random.nextInt(4) == 0) {
                    tuples.add(new Tuple(object, relation, subject(random, users, groups)));
                }
            }
        }
        // 通配授权。只挂在 viewer 上，刻意不挂 editor：editor 是 Minus 的 base，
        // 通配流到那里时展开路径是显式拒绝的（见 Expander.difference）。那条规则有专门的
        // 用例守着，混进随机场景只会把差分测试变成"在比两个适配器抛的异常是否一致"。
        for (var object : concat(folders, docs)) {
            if (random.nextInt(8) == 0) {
                tuples.add(new Tuple(object, VIEWER, new SubjectRef.Wildcard(USER)));
            }
        }

        var subjects = new ArrayList<SubjectRef>();
        users.forEach(user -> subjects.add(new SubjectRef.Principal(USER, user.id())));
        subjects.add(new SubjectRef.Userset(groups.getFirst(), MEMBER));
        // 一个没有任何元组的主体：通配是它唯一可能的放行路径。少了它，通配的效果会被
        // 那些本来就有授权的用户掩盖，差分测试就覆盖不到"仅靠通配放行"这条路。
        subjects.add(new SubjectRef.Principal(USER, "newcomer"));

        return new Generated(seed, schema, List.copyOf(tuples), List.copyOf(subjects),
                docs, Map.of("mfa", random.nextBoolean()));
    }

    private static Schema schema(Random random) {
        var types = new LinkedHashMap<ObjectType, Schema.TypeDef>();
        types.put(GROUP, new Schema.TypeDef(Map.of(MEMBER, Schema.tuples(MEMBER))));
        types.put(FOLDER, new Schema.TypeDef(relations(random)));
        types.put(DOC, new Schema.TypeDef(relations(random)));
        return new Schema(types);
    }

    private static Map<Rel, Schema.RelDef> relations(Random random) {
        var relations = new LinkedHashMap<Rel, Schema.RelDef>();
        relations.put(VIEWER, Schema.tuples(VIEWER));
        relations.put(EDITOR, Schema.tuples(EDITOR));
        relations.put(BANNED, Schema.tuples(BANNED));
        relations.put(PARENT, Schema.tuples(PARENT, FOLDER));
        relations.put(VIEW, Schema.computed(view(random), true));
        return relations;
    }

    /**
     * 随机的 view 定义。
     *
     * <p>第一项固定是 {@code Direct(viewer)}：递归定义必须有基础项，否则反查没有种子集。
     * 递归项固定是 {@code Through(parent, Ref(view))}，这是 {@code Planner} 唯一支持的递归形状。
     */
    private static Perm view(Random random) {
        var terms = new ArrayList<Perm>();
        terms.add(new Perm.Direct(VIEWER));
        if (random.nextBoolean()) {
            // 分层否定：banned 不在递归环里
            terms.add(new Perm.Minus(new Perm.Direct(EDITOR), new Perm.Direct(BANNED)));
        }
        if (random.nextBoolean()) {
            terms.add(new Perm.AllOf(List.of(new Perm.Direct(EDITOR), new Perm.Direct(VIEWER))));
        }
        if (random.nextBoolean()) {
            terms.add(new Perm.Guarded(new Perm.Direct(EDITOR), new Cond.Cmp(
                    Cond.Op.EQ, new Cond.Term.Attr(MFA), new Cond.Term.Lit(true))));
        }
        if (random.nextInt(4) > 0) {
            terms.add(new Perm.Through(PARENT, new Perm.Ref(VIEW)));
        }
        return new Perm.AnyOf(terms);
    }

    private static SubjectRef subject(Random random, List<ObjectRef> users, List<ObjectRef> groups) {
        if (random.nextInt(3) == 0) {
            return new SubjectRef.Userset(groups.get(random.nextInt(groups.size())), MEMBER);
        }
        var user = users.get(random.nextInt(users.size()));
        return new SubjectRef.Principal(USER, user.id());
    }

    private static List<ObjectRef> refs(ObjectType type, int count) {
        var out = new ArrayList<ObjectRef>(count);
        for (int i = 0; i < count; i++) {
            out.add(new ObjectRef(type, type.name().charAt(0) + String.valueOf(i)));
        }
        return List.copyOf(out);
    }

    private static List<ObjectRef> concat(List<ObjectRef> first, List<ObjectRef> second) {
        var out = new ArrayList<ObjectRef>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return out;
    }
}

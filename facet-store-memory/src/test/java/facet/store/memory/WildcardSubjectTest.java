package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.EvalException;
import facet.core.eval.Expander;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static facet.testkit.FolderScenario.BANNED;
import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.EDIT;
import static facet.testkit.FolderScenario.EDITOR;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.USER;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.principal;
import static facet.testkit.FolderScenario.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通配主体 {@code user:*}：一个开放集合在三条求值路径上的不对称。
 *
 * <p>它要解决的写放大是真实的：用组去模拟"所有登录用户都能看"，要求注册流程把每个新用户
 * 加进那个组——一次漏加就是一个看不见的权限缺口，而成员表会和用户表一样大。所以第一个用例
 * 断言的是<strong>没有任何元组的新用户也能看到公开资源</strong>，那才是这个形态的全部价值。
 *
 * <p>三条路径的分工必须分开钉住，因为它们对"开放集合"的处理根本不同：
 * <ul>
 *   <li>check 与反查都是针对<em>某个具体主体</em>求值，通配只是多一次匹配、多一行闭包种子，
 *       语义上没有任何新东西；</li>
 *   <li>展开要枚举主体，落不成具体的人，所以通配只能单独报进 {@code anyOf}。这就让
 *       "展开结果与逐个 check 一致"这条不变量有了两个组成部分，
 *       见 {@link #expansionAgreesWithCheckExceptForTheOpenSet}；</li>
 *   <li>{@code Minus} 的 base 上出现通配时展开直接拒绝——而同一份数据上 check 照常工作。
 *       这是唯一一处"某条路答不出来、另一条路答得出来"，必须有用例写明它是故意的。</li>
 * </ul>
 */
class WildcardSubjectTest {

    /** 被通配覆盖的公开文档，同时挂一条具体授权——两种主体共存才能验出返回顺序。 */
    private static final List<Tuple> OPEN = List.of(
            new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER)),
            Tuple.of(doc("public"), VIEWER, user("dave")));

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES).write(OPEN);
    private final MemoryAttrSource attrs = new MemoryAttrSource();
    private final Checker checker = new Checker(SCHEMA, tuples, attrs);
    private final Expander expander = new Expander(SCHEMA, tuples, attrs);

    /**
     * 这一条就是通配存在的理由：新注册的用户不需要任何写入就能看到公开资源。
     *
     * <p>{@code newcomer} 在库里一条元组都没有。用组模拟通配的方案在这里必须先有一次写入
     * 才能放行，而那次写入一旦漏掉就是一个沉默的权限缺口。
     */
    @Test
    void checkAllowsAnyPrincipalOfTheType() {
        for (var who : List.of("alice", "bob", "carol", "newcomer")) {
            assertTrue(Ctx.run(Ctx.Request.of(principal(who)),
                    () -> checker.check(doc("public"), VIEW)).allowed(), who);
        }
    }

    /**
     * 通配<strong>按类型</strong>，不是一个全局的"公开"。
     *
     * <p>多主体类型的部署要区分"所有 user"和"所有 service account"：把 {@code user:*}
     * 当成"任何人"会让一条给人开的授权顺带把所有机器账号也放进来。
     */
    @Test
    void checkDeniesOtherTypes() {
        var bot = new SubjectRef.Principal(new ObjectType("service"), "bot");

        assertFalse(Ctx.run(Ctx.Request.of(bot),
                () -> checker.check(doc("public"), VIEW)).allowed());
    }

    /**
     * 反查同样要看到通配授权：主体闭包里多播一颗 {@code user:*} 的种子即可。
     *
     * <p>漏掉这颗种子的症状最难查——check 说"你能看"，而列表接口里这份文档根本不出现。
     */
    @Test
    void reverseLookupFindsWildcardGrantedObjects() {
        var plan = new Planner(SCHEMA, tuples.caps()).plan(DOC, VIEW, Cursor.START, 10);
        var executor = new MemoryPlanExecutor(tuples, attrs);

        var found = Ctx.run(Ctx.Request.of(principal("newcomer")),
                () -> executor.execute(plan).toList());

        assertEquals(List.of(doc("public")), found);
    }

    /**
     * 展开把通配单独报出来，而不是编一个叫"everyone"的假主体。
     *
     * <p>两件事都不能做：静默丢掉它会让"谁能看这份文档"漏掉"所有人"——对审计来说低估访问面
     * 是最危险的方向；编一个占位主体则会让调用方把它当成一个能加进群组、能撤销的真实账号。
     */
    @Test
    void expansionReportsWildcardSeparately() {
        var found = subjects(doc("public"));

        assertEquals(List.of(USER), List.copyOf(found.anyOf()));
        // principals 里只有那条具体授权：通配没有被伪造成任何一个人
        assertEquals(List.of(principal("dave")), List.copyOf(found.principals()));
    }

    /**
     * 展开与 check 的一致性，在结果分成两部分之后的正确形式。
     *
     * <p>原来的不变量是"出现在展开结果里当且仅当 check 放行"。通配加进来之后它必须写成
     * <strong>principals 命中<em>或</em>anyOf 覆盖其类型</strong>——只对前半部分断言的话，
     * 权限界面会变成"页面上没有他，但他确实能打开"，而这正是通配最容易踩的坑。
     */
    @Test
    void expansionAgreesWithCheckExceptForTheOpenSet() {
        var candidates = List.of(principal("alice"), principal("bob"),
                principal("carol"), principal("newcomer"));

        for (var object : List.of(doc("public"), doc("private"), doc("readme"))) {
            var found = subjects(object);
            for (var candidate : candidates) {
                boolean allowed = Ctx.run(Ctx.Request.of(candidate),
                        () -> checker.check(object, VIEW)).allowed();

                assertEquals(allowed,
                        found.principals().contains(candidate)
                                || found.anyOf().contains(candidate.type()),
                        candidate + " 在 " + object + "#" + VIEW.name()
                                + " 上：check=" + allowed + "，展开结果=" + found);
            }
        }
    }

    /**
     * {@code Minus} 的 base 上留下通配、否定侧又有具体主体时，展开拒绝作答。
     *
     * <p>真实答案是"这个类型的全部主体，除了某几个"。忠实表达它要在结果里再带一个排除集，
     * 而那会渗进 wire 格式和每一个消费方；静默把通配丢掉则会让审计低估访问面。宁可拒绝。
     *
     * <p>后半段同样重要：同一份数据上 check 完全正常——alice 被 banned 挡住、bob 靠通配放行。
     * 拒绝是<strong>展开路径独有的</strong>，不是这份数据或这份 schema 不合法，所以它也不可能
     * 做成加载期规则：通配是否流到 {@code Minus} 的 base 由数据决定。
     */
    @Test
    void wildcardIntoMinusBaseIsRejected() {
        // FolderScenario 的 edit 本来就是 editor - banned，不必另造 schema
        var mixed = new MemoryTupleSource().write(
                new Tuple(doc("shared"), EDITOR, new SubjectRef.Wildcard(USER)),
                Tuple.of(doc("shared"), BANNED, user("alice")));
        var expanding = new Expander(SCHEMA, mixed, attrs);

        var failure = assertThrows(EvalException.class,
                () -> Ctx.run(Ctx.Request.of(principal("placeholder")),
                        () -> expanding.subjects(doc("shared"), EDIT, Cursor.START, 100)));
        assertTrue(failure.getMessage().contains("无法表达"), failure.getMessage());

        var checking = new Checker(SCHEMA, mixed, attrs);
        assertFalse(Ctx.run(Ctx.Request.of(principal("alice")),
                () -> checking.check(doc("shared"), EDIT)).allowed(), "alice 被 banned 挡住");
        assertTrue(Ctx.run(Ctx.Request.of(principal("bob")),
                () -> checking.check(doc("shared"), EDIT)).allowed(), "bob 靠通配放行");
    }

    /**
     * {@code *} 不能做具体主体的 id。
     *
     * <p>它是通配在<strong>线上格式</strong>里的写法（{@code user:*}）。允许它做具体 id，
     * wire 上就再也分不清"所有用户"和"id 是星号的那个用户"——一个授权 API 不该有这种歧义。
     */
    @Test
    void principalIdCannotBeTheWildcardLiteral() {
        assertThrows(IllegalArgumentException.class, () -> new SubjectRef.Principal(USER, "*"));
    }

    /**
     * 通配排在同类型的全部具体主体之前。
     *
     * <p>不是美观问题：它的 id 在表里编码成空串，而端口要求的顺序刻意与 SQL 的
     * {@code (subject_type, subject_id, subject_rel)} 对齐。这条断言钉住的就是"内存适配器的
     * 顺序与 PG 的 {@code COLLATE "C"} 是同一个序"——否则 explain 的分支顺序会随存储实现变化。
     */
    @Test
    void wildcardSortsBeforeConcretePrincipals() {
        var found = Ctx.run(Ctx.Request.of(principal("placeholder")),
                () -> tuples.subjects(doc("public"), VIEWER));

        assertEquals(List.of(new SubjectRef.Wildcard(USER), principal("dave")), List.copyOf(found));
    }

    /** 展开不针对某个主体，上下文里的 principal 只是占位。 */
    private Expander.Subjects subjects(ObjectRef object) {
        return Ctx.run(Ctx.Request.of(principal("placeholder")),
                () -> expander.subjects(object, VIEW, Cursor.START, 100));
    }
}

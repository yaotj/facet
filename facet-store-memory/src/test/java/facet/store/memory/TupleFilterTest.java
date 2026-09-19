package facet.store.memory;

import facet.core.eval.Ctx;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.core.ir.TupleFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static facet.testkit.FolderScenario.EDITOR;
import static facet.testkit.FolderScenario.GROUP;
import static facet.testkit.FolderScenario.MEMBER;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.USER;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.group;
import static facet.testkit.FolderScenario.principal;
import static facet.testkit.FolderScenario.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按条件读回元组。
 *
 * <p>{@code TupleSource} 上那三个读方法都是<em>求值</em>形状的，每个都要求一个完整锚点，
 * 答不出"这个对象上到底写了什么"和"这个人被授了哪些权"。而这两个问题是迁移、审计导出、
 * 离职清理的基本操作——没有它们，数据写进去之后就没法清理，因为撤销要求逐条精确指定。
 */
class TupleFilterTest {

    private final MemoryTupleSource tuples = new MemoryTupleSource().write(TUPLES);

    /** {@code null} 字段就是"任意"，所以只钉住对象的筛选条件会命中它上面的全部关系。 */
    @Test
    void filterByObjectReturnsEveryRelationOnIt() {
        var found = read(TupleFilter.onObject(doc("readme")));

        assertEquals(List.of(
                new Tuple(doc("readme"), new Rel("banned"), principal("alice")),
                new Tuple(doc("readme"), EDITOR, principal("alice")),
                new Tuple(doc("readme"), new Rel("parent"),
                        new SubjectRef.Principal(folder("eng").type(), "eng"))),
                found);
    }

    @Test
    void filterByObjectAndRelationNarrowsFurther() {
        assertEquals(List.of(new Tuple(doc("readme"), EDITOR, principal("alice"))),
                read(TupleFilter.onObject(doc("readme"), EDITOR)));
    }

    /**
     * 按主体筛选——离职清理的入口。
     *
     * <p>注意它只匹配<strong>直接</strong>授权。carol 经 {@code group:eng#member} 间接拿到的
     * 权限不在结果里，因为那条元组的主体侧是组而不是人。
     */
    @Test
    void filterBySubjectFindsDirectGrantsOnly() {
        var carol = read(TupleFilter.ofSubject(principal("carol")));

        assertEquals(List.of(new Tuple(group("eng"), MEMBER, principal("carol"))), carol);
    }

    /** 按主体筛选 userset：要连带清掉组授权时用这个形态。 */
    @Test
    void filterByUsersetSubject() {
        var found = read(TupleFilter.ofSubject(
                new SubjectRef.Userset(group("eng"), MEMBER)));

        assertEquals(List.of(new Tuple(folder("eng"), VIEWER,
                new SubjectRef.Userset(group("eng"), MEMBER))), found);
    }

    @Test
    void filterByObjectTypeCoversTheWholeType() {
        var found = read(TupleFilter.ofObjectType(GROUP));

        assertEquals(List.of(new Tuple(group("eng"), MEMBER, principal("carol"))), found);
    }

    /** 无约束的筛选条件匹配全部。它是合法的，但必须显式构造出来。 */
    @Test
    void anyMatchesEverything() {
        assertEquals(TUPLES.size(), read(TupleFilter.ANY).size());
        assertTrue(TupleFilter.ANY.unconstrained());
        assertFalse(TupleFilter.onObject(doc("readme")).unconstrained());
    }

    /**
     * 空白串不是"任意"。
     *
     * <p>空串在 {@code subject_rel} 上有具体含义——它表示"主体是具体对象而非 userset"。
     * 让空串和 null 都当"任意"，就再也分不清"我要匹配具体主体"和"我这个字段忘填了"。
     */
    @Test
    void blankIsRejectedRatherThanTreatedAsAny() {
        assertThrows(IllegalArgumentException.class,
                () -> new TupleFilter(null, "  ", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TupleFilter(null, null, null, null, "", null));
    }

    /** 游标分页走完整个结果集，不漏不重；排序与游标比较是同一个序。 */
    @Test
    void cursorPaginationWalksTheWholeSet() {
        var walked = new ArrayList<Tuple>();
        Tuple after = null;
        while (true) {
            var cursor = after;
            var page = Ctx.run(Ctx.Request.of(principal("admin")),
                    () -> tuples.read(TupleFilter.ANY, cursor, 3));
            if (page.isEmpty()) {
                break;
            }
            walked.addAll(page);
            after = page.getLast();
        }

        assertEquals(read(TupleFilter.ANY), walked);
        assertEquals(TUPLES.size(), walked.size());
    }

    @Test
    void limitMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> Ctx.run(Ctx.Request.of(principal("admin")),
                        () -> tuples.read(TupleFilter.ANY, null, 0)));
    }

    /**
     * 通配筛选只命中通配授权，不碰该类型下任何具体主体的授权。
     *
     * <p>这是那个 bug 的回归测试。它不是"少返回几条"的问题：这个条件会流进
     * {@code revokeWhere}，匹配多了就是"下线一条公开策略"变成"撤销该类型下所有人的授权"。
     * 而 {@link TupleFilter#unconstrained()} 在这种条件上返回 {@code false}，
     * 运维日志里连个警告都不会有——事后只能靠对账发现。
     */
    @Test
    void wildcardFilterDoesNotMatchConcreteGrants() {
        var wildcard = new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));
        var concrete = Tuple.of(doc("public"), VIEWER, user("dave"));
        var source = new MemoryTupleSource().write(TUPLES).write(wildcard, concrete);

        assertEquals(List.of(wildcard), read(source, TupleFilter.wildcardsOf(USER)));
    }

    /** {@code ofSubject(Wildcard)} 委派给 {@code wildcardsOf}，两者必须给出同一个结果。 */
    @Test
    void ofSubjectWithWildcardIsPreciseToo() {
        var wildcard = new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));
        var concrete = Tuple.of(doc("public"), VIEWER, user("dave"));
        var source = new MemoryTupleSource().write(TUPLES).write(wildcard, concrete);

        assertEquals(TupleFilter.wildcardsOf(USER),
                TupleFilter.ofSubject(new SubjectRef.Wildcard(USER)));
        assertEquals(List.of(wildcard),
                read(source, TupleFilter.ofSubject(new SubjectRef.Wildcard(USER))));
    }

    /** 反过来也要成立：按具体主体清理不该把公开策略一起带走。 */
    @Test
    void concreteSubjectFilterDoesNotMatchTheWildcard() {
        var wildcard = new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));
        var concrete = Tuple.of(doc("public"), VIEWER, user("dave"));
        var source = new MemoryTupleSource().write(TUPLES).write(wildcard, concrete);

        assertEquals(List.of(concrete), read(source, TupleFilter.ofSubject(principal("dave"))));
    }

    /**
     * {@code "*"} 是筛选层的通配标记，{@code matches} 把它钉死在通配主体上。
     *
     * <p>借用这个 id 是安全的：{@code Principal} 的构造器拒绝它，所以它不可能撞上真实主体。
     * 各适配器再把它翻译成自己的存储编码（PG 与内存都是空串）。
     */
    @Test
    void matchesTreatsWildcardIdAsTheMarker() {
        var wildcard = new Tuple(doc("public"), VIEWER, new SubjectRef.Wildcard(USER));
        var concrete = Tuple.of(doc("public"), VIEWER, user("dave"));

        assertTrue(TupleFilter.wildcardsOf(USER).matches(wildcard));
        assertFalse(TupleFilter.wildcardsOf(USER).matches(concrete));
        assertEquals(SubjectRef.WILDCARD_ID, TupleFilter.wildcardsOf(USER).subjectId());
        assertThrows(IllegalArgumentException.class,
                () -> new SubjectRef.Principal(USER, SubjectRef.WILDCARD_ID));
    }

    /** {@code matches} 是筛选语义的单一定义：PG 侧的 WHERE 必须与它等价。 */
    @Test
    void matchesIsTheSingleDefinitionOfTheFilter() {
        var tuple = new Tuple(doc("readme"), EDITOR, principal("alice"));

        assertTrue(TupleFilter.ANY.matches(tuple));
        assertTrue(TupleFilter.onObject(doc("readme")).matches(tuple));
        assertTrue(TupleFilter.ofSubject(principal("alice")).matches(tuple));
        assertFalse(TupleFilter.onObject(doc("spec")).matches(tuple));
        assertFalse(TupleFilter.onObject(doc("readme"), VIEWER).matches(tuple));
        // 要求匹配某个 subjectRel 时，具体主体一律不符合——它没有 rel
        assertFalse(new TupleFilter(null, null, null, USER, "alice", MEMBER).matches(tuple));
    }

    private List<Tuple> read(TupleFilter filter) {
        return read(tuples, filter);
    }

    /**
     * 在指定的存储上读。
     *
     * <p>通配那几条用局部存储而不是加进类级 fixture：{@code anyMatchesEverything} 与
     * {@code filterByObjectReturnsEveryRelationOnIt} 断言的是精确条数与精确清单，
     * 往共享 fixture 里加一条元组会把它们一起弄红。
     */
    private static List<Tuple> read(MemoryTupleSource source, TupleFilter filter) {
        return Ctx.run(Ctx.Request.of(principal("admin")),
                () -> source.read(filter, null, 1000));
    }
}

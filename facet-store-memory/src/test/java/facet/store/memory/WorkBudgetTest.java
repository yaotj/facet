package facet.store.memory;

import facet.core.eval.Checker;
import facet.core.runtime.Ctx;
import facet.core.runtime.EvalException;
import facet.core.runtime.Explains;
import facet.core.ir.ObjectRef;
import facet.core.ir.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static facet.testkit.FolderScenario.PARENT;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.TUPLES;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static facet.testkit.FolderScenario.doc;
import static facet.testkit.FolderScenario.folder;
import static facet.testkit.FolderScenario.principal;
import static facet.testkit.FolderScenario.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单次判定的工作预算。
 *
 * <p>它补的是 {@code maxDepth} 与 {@code maxFanout} 都盖不住的那一块：前者限一条<em>路径</em>
 * 的长度，后者限一个<em>算子</em>的宽度，都不限整棵树的大小。
 *
 * <p>第一个用例里的图是关键：每层四个节点、每个节点指向下一层全部四个节点，每个算子的扇出
 * 都是 4（远低于上限 1024），每条路径都在深度上限之内，但路径数是 4 的层数次方。更糟的是
 * 一旦有路径撞到 {@code maxDepth}，那条路径上的节点因为带了剪枝标记而全部进不了记忆化
 * （见 {@code Memo#put}），解释器于是从"每个节点算一次"退化成"枚举每条路径"。
 * 在真实存储上，每个节点是一次 JDBC 往返——一个认证用户的一次 {@code POST /v1/check}
 * 就能把连接池抽干。
 */
class WorkBudgetTest {

    /** 每层节点数，同时也是每个 Through 的扇出宽度。远低于内存适配器的 1024 上限。 */
    private static final int WIDTH = 4;
    private static final int LEVELS = 12;

    private final MemoryAttrSource attrs = new MemoryAttrSource();

    /** 默认预算下这种形状被<strong>拒绝</strong>，而不是把存储打穿。 */
    @Test
    void latticeIsRejectedUnderTheDefaultBudget() {
        var checker = new Checker(SCHEMA, lattice(WIDTH, LEVELS), attrs);

        var thrown = assertThrows(EvalException.class,
                () -> Ctx.run(Ctx.Request.of(principal("dave")),
                        () -> checker.check(folder("f0_0"), VIEW)));

        assertTrue(thrown.getMessage().contains("节点数超过预算"), thrown.getMessage());
    }

    /**
     * 超预算是抛异常，不是判 deny。
     *
     * <p>这一条是这个机制的要害。落成 deny 的话，一次资源不足会被读成"确实无权限"——
     * 和判定树里出现 {@code DEPTH-EXCEEDED} 的情况一样是"未知"，但 deny 会被调用方当成答案。
     */
    @Test
    void exhaustionIsNotADeny() {
        var checker = new Checker(SCHEMA, lattice(WIDTH, LEVELS), attrs);

        assertThrows(EvalException.class, () -> Ctx.run(Ctx.Request.of(principal("dave")),
                () -> checker.check(folder("f0_0"), VIEW).allowed()));
    }

    /**
     * 抬高预算之后同一次判定能给出结论：预算是兜底，不是把这类形状永久禁掉。
     *
     * <p>这里用的是宽度 2 的窄格，因为宽度 4 那张图在默认预算的两个数量级之上仍然跑不完
     * ——这本身就是"退化是指数级的、不是常数倍"的直接证据。
     */
    @Test
    void raisingTheBudgetLetsItFinish() {
        var checker = new Checker(SCHEMA, lattice(2, LEVELS), attrs);
        var generous = Ctx.Request.of(principal("dave")).withMaxNodes(200_000);

        var decision = Ctx.run(generous, () -> checker.check(folder("f0_0"), VIEW));

        assertFalse(decision.allowed());
        // 结论是 deny，但语义是"未知"：判定树里有深度截断
        assertTrue(Explains.render(decision.explain()).contains("DEPTH-EXCEEDED"));
    }

    /** 正常形状离预算很远：两层继承的一次判定只花个位数节点。 */
    @Test
    void ordinaryShapesAreNowhereNearTheBudget() {
        var checker = new Checker(SCHEMA, new MemoryTupleSource().write(TUPLES), attrs);
        var tight = Ctx.Request.of(principal("alice")).withMaxNodes(16);

        assertTrue(Ctx.run(tight, () -> checker.check(doc("deep"), VIEW)).allowed());
    }

    /**
     * 预算按<strong>每次判定</strong>算，不是按每个请求。
     *
     * <p>否则批量判定会自己饿死：200 个对象共用一份预算，等于每个对象只剩两百分之一。
     * 批量的正当开销本来就与对象数成正比，把预算做成请求级的会让"一次问 200 个"
     * 和"问 200 次"在资源上被区别对待，而两者做的是同一件事。
     */
    @Test
    void theBudgetIsPerCheckNotPerRequest() {
        var tuples = new MemoryTupleSource();
        var docs = new ArrayList<ObjectRef>();
        var written = new ArrayList<Tuple>();
        for (int i = 0; i < 100; i++) {
            var object = doc("d" + i);
            docs.add(object);
            written.add(Tuple.of(object, VIEWER, user("alice")));
        }
        tuples.write(written);
        var checker = new Checker(SCHEMA, tuples, attrs);
        // 单次判定绰绰有余，但若预算是请求级的，100 个对象合起来一定会超
        var tight = Ctx.Request.of(principal("alice")).withMaxNodes(8);

        var answers = Ctx.run(tight, () -> checker.checkAll(docs, VIEW));

        assertEquals(100, answers.size());
        assertTrue(answers.values().stream().allMatch(decision -> decision.allowed()));
    }

    @Test
    void budgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> Ctx.Request.of(principal("alice")).withMaxNodes(0));
    }

    /**
     * 每层 {@code width} 个节点，每个节点指向下一层的全部节点。
     *
     * <p>没有任何 viewer 元组：让判定必须走完能走的每一条路，不会因为提前命中而短路。
     */
    private static MemoryTupleSource lattice(int width, int levels) {
        var written = new ArrayList<Tuple>();
        for (int level = 0; level < levels - 1; level++) {
            for (int from = 0; from < width; from++) {
                for (int to = 0; to < width; to++) {
                    written.add(Tuple.of(folder("f" + level + '_' + from), PARENT,
                            folder("f" + (level + 1) + '_' + to)));
                }
            }
        }
        return new MemoryTupleSource().write(List.copyOf(written));
    }
}

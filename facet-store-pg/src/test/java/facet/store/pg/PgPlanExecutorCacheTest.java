package facet.store.pg;

import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.Plan;
import facet.core.spi.TupleSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static facet.testkit.FolderScenario.DOC;
import static facet.testkit.FolderScenario.SCHEMA;
import static facet.testkit.FolderScenario.VIEW;
import static facet.testkit.FolderScenario.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编译缓存的边界。
 *
 * <p>不需要数据库：编译是纯函数，这些断言管的是缓存本身。
 *
 * <p>归一化让键空间等于 schema 的形状数，看起来小到不必设上限——但那个前提在两处会破：
 * 热更新引入新形状而旧形状永不淘汰（PDP 换 schema 时不重建执行器），手工拼计划的调用方
 * 也不受 schema 约束。容量上限是不依赖这些前提的兜底。
 */
class PgPlanExecutorCacheTest {

    private static final TupleSource.Caps FULL = new TupleSource.Caps(true, true, true, 1024);

    /** 连接会抛：确认这些用例真的没碰数据库。 */
    private static final Connections NO_DATABASE = () -> {
        throw new SQLException("这个用例不该建连接");
    };

    private final Planner planner = new Planner(SCHEMA, FULL);
    private final PgPlanExecutor executor = new PgPlanExecutor(NO_DATABASE);

    /** 游标与页大小走绑定参数：同一形状换一万个游标仍然只占一个缓存位。 */
    @Test
    void cursorsDoNotMultiplyShapes() {
        var first = executor.explainSql(planner.plan(DOC, VIEW, Cursor.START, 10));
        for (int i = 0; i < 10_000; i++) {
            executor.explainSql(planner.plan(DOC, VIEW, new Cursor("doc:d" + i), 1 + i % 50));
        }

        assertEquals(1, executor.compiledShapes());
        // 同一个编译结果，不是每次重编出一个相等的对象
        assertSame(first, executor.explainSql(planner.plan(DOC, VIEW, Cursor.START, 7)));
    }

    /**
     * 形状数超过上限时淘汰，不无界增长。
     *
     * <p>用手工拼的计划制造形状：它们不受 schema 约束，正是"键空间等于 schema 形状数"
     * 这个前提破掉的那种情形。
     */
    @Test
    void shapesBeyondTheCapAreEvicted() {
        for (int i = 0; i < 2_000; i++) {
            executor.explainSql(new Plan.Page(
                    new Plan.Union(List.of(
                            new Plan.ScanReverse(VIEWER, new facet.core.ir.ObjectType("t" + i)),
                            new Plan.ScanReverse(VIEWER, DOC))),
                    Cursor.START, 10));
        }

        assertTrue(executor.compiledShapes() <= 256,
                "缓存应当有界，实际 " + executor.compiledShapes());
    }

    /**
     * 顶层没有 {@code Page} 的计划直接拒绝。
     *
     * <p>{@code Page} 是这个 IR 里唯一表达"最多要多少"的算子。反查的结果集大小由数据决定，
     * 顶层没有它，适配器就只能把整个结果集拉回来——而这一步在拉完之前没人知道有多大。
     */
    @Test
    void unpagedPlansAreRejected() {
        var unpaged = new Plan.ScanReverse(VIEWER, DOC);

        var thrown = assertThrows(IllegalArgumentException.class, () -> executor.execute(unpaged));

        assertTrue(thrown.getMessage().contains("Plan.Page"), thrown.getMessage());
    }
}

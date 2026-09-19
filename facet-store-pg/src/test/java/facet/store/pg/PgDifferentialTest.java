package facet.store.pg;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Expander;
import facet.core.eval.Keys;
import facet.core.eval.Planner;
import facet.core.eval.Validator;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import facet.testkit.DecisionMatrix;
import facet.testkit.RandomScenario;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨适配器差分测试。
 *
 * <p>{@code PgConsistencyTest} 用的是一份手写场景，能证明"这一份数据上两个实现一致"；
 * 这里换成随机 schema 加随机数据，证明的是"任意形状下都一致"。已经找出来的三处分歧
 * （{@code BigDecimal} 比精度、不可变 Set 迭代顺序随机、排序规则不对齐）都属于
 * 固定场景撞不到的类型。
 *
 * <p>每个种子都清库重灌：种子之间必须互不污染，否则一次失败无法归因到具体形状。
 *
 * <p>三条求值路径全都比对——check、反查、展开。只比前两条的话，展开那条路上适配器
 * 特有的行为（返回顺序、userset 形态、空集表示）就没有任何断言看着。
 */
@Testcontainers(disabledWithoutDocker = true)
class PgDifferentialTest {

    /** 每个种子要清库重灌加数十次查询，所以比纯内存的差分测试少跑几轮。 */
    private static final int SEEDS = 12;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static Connections connections;
    private static PgTupleSource pgTuples;
    private static PgAttrSource pgAttrs;
    private static PgPlanExecutor pgExecutor;

    @BeforeAll
    static void setUp() {
        connections = () -> DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        pgTuples = new PgTupleSource(connections);
        pgTuples.migrate();
        pgAttrs = new PgAttrSource(connections);
        pgExecutor = new PgPlanExecutor(connections);
    }

    @Test
    void adaptersAgreeOnRandomScenarios() {
        for (long seed = 0; seed < SEEDS; seed++) {
            var scenario = RandomScenario.of(seed);
            Validator.validate(scenario.schema());

            truncate();
            pgTuples.write(new Revision(1), scenario.tuples());
            var memTuples = new MemoryTupleSource().write(scenario.tuples());
            var memAttrs = new MemoryAttrSource();

            var relations = List.of(RandomScenario.VIEW);
            var memory = DecisionMatrix.render(scenario.subjects(), scenario.docs(), relations,
                    scenario.context(), new Checker(scenario.schema(), memTuples, memAttrs)::check);
            var postgres = DecisionMatrix.render(scenario.subjects(), scenario.docs(), relations,
                    scenario.context(), new Checker(scenario.schema(), pgTuples, pgAttrs)::check);
            assertEquals(memory, postgres, "判定树在 seed=" + seed + " 上分歧");

            var memPlanner = new Planner(scenario.schema(), memTuples.caps());
            var pgPlanner = new Planner(scenario.schema(), pgTuples.caps());
            var memExecutor = new MemoryPlanExecutor(memTuples, memAttrs);
            for (var subject : scenario.subjects()) {
                assertEquals(
                        lookupAll(scenario, subject, memPlanner, plan -> memExecutor.execute(plan).toList()),
                        lookupAll(scenario, subject, pgPlanner, plan -> pgExecutor.execute(plan).toList()),
                        "反查结果在 seed=" + seed + " subject=" + subject + " 上分歧");
            }

            // 第三条路径同样要对齐：展开用的是 subjects()/targets()，和 check 走的是
            // 同一组端口但不同的调用形状（一次取全部主体，而不是问"包含我吗"），
            // 所以适配器的返回顺序、userset 形态、空集表示都在这里被重新检验一遍。
            var memExpander = new Expander(scenario.schema(), memTuples, memAttrs);
            var pgExpander = new Expander(scenario.schema(), pgTuples, pgAttrs);
            for (var object : scenario.docs()) {
                var memoryFound = expand(scenario, memExpander, object);
                var pgFound = expand(scenario, pgExpander, object);
                // 两部分分开比：principals 用 List 比而不是 Set，因为返回顺序也是端口契约；
                // anyOf 是通配主体类型，它在两个适配器里的编码不同（内存是 Wildcard，
                // PG 是 subject_id = ''），不单独比一遍这层转换就没有断言看着
                assertEquals(List.copyOf(memoryFound.principals()), List.copyOf(pgFound.principals()),
                        "展开结果在 seed=" + seed + " object=" + object + " 上分歧");
                assertEquals(List.copyOf(memoryFound.anyOf()), List.copyOf(pgFound.anyOf()),
                        "通配主体类型在 seed=" + seed + " object=" + object + " 上分歧");
            }
        }
    }

    /** 展开不针对某个主体，上下文里的 principal 只是占位。 */
    private static Expander.Subjects expand(RandomScenario.Generated scenario,
                                            Expander expander, ObjectRef object) {
        var request = Ctx.Request.of(scenario.subjects().getFirst())
                .withContextAttrs(scenario.context());
        return Ctx.run(request, () -> expander.subjects(object, RandomScenario.VIEW, Cursor.START, 10000));
    }

    private static List<ObjectRef> lookupAll(RandomScenario.Generated scenario, SubjectRef subject,
                                             Planner planner, Lookup lookup) {
        var request = Ctx.Request.of(subject).withContextAttrs(scenario.context());
        var found = new ArrayList<ObjectRef>();
        var cursor = Cursor.START;
        while (true) {
            var plan = planner.plan(RandomScenario.DOC, RandomScenario.VIEW, cursor, 3);
            var page = Ctx.run(request, () -> lookup.execute(plan));
            if (page.isEmpty()) {
                break;
            }
            found.addAll(page);
            cursor = Cursor.of(page.getLast());
        }
        return found.stream().sorted(Comparator.comparing(Cursor::keyOf, Keys.ORDER)).toList();
    }

    @FunctionalInterface
    private interface Lookup {
        List<ObjectRef> execute(facet.core.ir.Plan plan);
    }

    private static void truncate() {
        try (var conn = connections.get(); var st = conn.createStatement()) {
            st.execute("TRUNCATE facet_tuple");
        } catch (SQLException e) {
            throw new IllegalStateException("清库失败", e);
        }
    }
}

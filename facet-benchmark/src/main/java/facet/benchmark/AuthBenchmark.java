package facet.benchmark;

import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Planner;
import facet.core.eval.Schema;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.dsl.rebac.Rebac;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static facet.dsl.rebac.Rebac.anyOf;
import static facet.dsl.rebac.Rebac.direct;
import static facet.dsl.rebac.Rebac.ref;
import static facet.dsl.rebac.Rebac.through;

/**
 * Facet 关键路径的可复跑基准。三条被测路径对应 README 里"它解决的真实痛点"：
 *
 * <ul>
 *   <li>{@link #checkShallowInherit()} / {@link #checkDeepInherit()} —— {@code check}：能不能看。
 *   浅继承是单跳，深继承走满整条目录链，用来观察递归求值的成本随深度增长。</li>
 *   <li>{@link #lookupResources()} —— {@code lookupResources}：能看哪些。这是 Facet 相对
 *   "全表拉回来逐条 check" 的核心价值，基准测的是反查计划编译 + 执行、而不是应用层循环。</li>
 * </ul>
 *
 * 数据全部在内存（{@code MemoryTupleSource}），不依赖数据库，任何机器都能复跑。
 * 跑法见 {@link BenchmarkMain}。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class AuthBenchmark {

    private static final ObjectType USER = new ObjectType("user");
    private static final ObjectType FOLDER = new ObjectType("folder");
    private static final ObjectType DOC = new ObjectType("doc");
    private static final Rel VIEWER = new Rel("viewer");
    private static final Rel PARENT = new Rel("parent");
    private static final Rel VIEW = new Rel("view");

    /** 目录链深度。doc 挂在最深的 folder 上时，check 要遍历整条链。 */
    private static final int DEPTH = 8;
    /** 反查数据集里的文档数。 */
    private static final int DOCS = 200;

    private Schema schema;
    private MemoryTupleSource tuples;
    private MemoryAttrSource attrs;
    private SubjectRef alice;
    private Checker checker;
    private Planner planner;
    private MemoryPlanExecutor executor;
    private ObjectRef deepDoc;

    @Setup(Level.Trial)
    public void setup() {
        var chain = anyOf(direct("viewer"), through("parent", ref("view")));
        schema = Rebac.define()
                .type("folder", t -> t
                        .tuples("viewer")
                        .tuples("parent", "folder")
                        .listable("view", chain))
                .type("doc", t -> t
                        .tuples("viewer")
                        .tuples("parent", "folder")
                        .listable("view", chain))
                .build();

        var writes = new ArrayList<Tuple>();
        // alice 是根目录 f0 的 viewer；f1..fDEPTH 各自 parent 指向上一层
        writes.add(Tuple.of(new ObjectRef(FOLDER, "f0"), VIEWER, new ObjectRef(USER, "alice")));
        for (int i = 1; i <= DEPTH; i++) {
            writes.add(Tuple.of(new ObjectRef(FOLDER, "f" + i), PARENT, new ObjectRef(FOLDER, "f" + (i - 1))));
        }
        // DOCS 个文档，均匀挂到链上的各个 folder（都从 f0 继承到 alice 的 view）
        for (int i = 0; i < DOCS; i++) {
            int f = i % (DEPTH + 1);
            writes.add(Tuple.of(new ObjectRef(DOC, "d" + i), PARENT, new ObjectRef(FOLDER, "f" + f)));
        }

        tuples = new MemoryTupleSource().write(writes.toArray(new Tuple[0]));
        attrs = new MemoryAttrSource();
        alice = new SubjectRef.Principal(USER, "alice");
        checker = new Checker(schema, tuples, attrs);
        planner = new Planner(schema, tuples.caps());
        executor = new MemoryPlanExecutor(tuples, attrs);
        deepDoc = new ObjectRef(DOC, "d" + (DOCS - 1)); // 挂在 fDEPTH 下，check 遍历整条链
    }

    @Benchmark
    public boolean checkShallowInherit() {
        // 挂在 f0 下：单跳即可命中 alice 的 viewer
        return Ctx.run(Ctx.Request.of(alice),
                () -> checker.check(new ObjectRef(DOC, "d0"), VIEW).allowed());
    }

    @Benchmark
    public boolean checkDeepInherit() {
        // 挂在最深的 folder 下：遍历整条 DEPTH 层链
        return Ctx.run(Ctx.Request.of(alice), () -> checker.check(deepDoc, VIEW).allowed());
    }

    @Benchmark
    public List<ObjectRef> lookupResources() {
        var plan = planner.plan(DOC, VIEW, Cursor.START, DOCS);
        return Ctx.run(Ctx.Request.of(alice), () -> executor.execute(plan).toList());
    }
}

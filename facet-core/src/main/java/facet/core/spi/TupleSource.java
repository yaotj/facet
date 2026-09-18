package facet.core.spi;

import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 元组存储端口。
 *
 * <p>方法签名里没有一致性坐标：{@code Revision} 从 {@code Ctx.CURRENT} 取。参数上的
 * 版本号总会有人传 null，上下文里的字段不能省。
 *
 * <p><strong>返回顺序是契约的一部分。</strong>判定树的分支顺序直接来自这里的迭代顺序，
 * 顺序不确定就意味着同一份数据在两个适配器上给出不同的 explain，跨适配器的判定矩阵
 * 永远对不上。注意 {@code Set.copyOf} / {@code Collectors.toUnmodifiableSet} 的迭代顺序
 * 是按 JVM 启动随机化的，不能用来兜住顺序。
 *
 * <p><strong>实现应当把扇出上限下推。</strong>{@code Caps.maxFanout} 的校验在
 * {@code Checker} 里发生，但那是数据已经加载完之后；热点对象上百万条元组会先把内存打满
 * 再报错。适配器应在查询里就带上 {@code LIMIT maxFanout + 1}。
 */
public interface TupleSource {

    /**
     * 正向：{@code obj#rel@?}。check 路径的基本操作。
     *
     * <p>必须按 {@link SubjectRef#ORDER} 返回。
     */
    Set<SubjectRef> subjects(ObjectRef obj, Rel rel);

    /**
     * 跳：{@code obj#hop@o'} 里的 o'，只取具体对象（{@code Userset} 不是跳的目标）。
     *
     * <p>默认实现从 {@link #subjects} 过滤而来，因此天然继承它的顺序契约。刻意给成
     * {@code default}：它只是一次投影，任何适配器复制一份都会成为两个存储上
     * {@code Through} 语义分叉的入口。真能下推的适配器再 override。
     */
    default Set<ObjectRef> targets(ObjectRef obj, Rel hop) {
        return subjects(obj, hop).stream()
                .filter(SubjectRef.Principal.class::isInstance)
                .map(SubjectRef.Principal.class::cast)
                .map(p -> new ObjectRef(p.type(), p.id()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 反向：主体在 {@code rel} 上直接命中的该类型对象。反查路径的基本操作。 */
    Stream<ObjectRef> objects(SubjectRef subject, Rel rel, ObjectType type);

    Caps caps();

    /**
     * 能力声明。
     *
     * <p>{@code Planner} 在编译期读它：没有 {@code reverseIndex} 就直接拒绝
     * {@code lookupResources}，没有 {@code recursiveQuery} 就拒绝递归定义的反查。
     * 不做"全表扫再过滤"或"应用层循环展开"的静默降级——那种降级会在数据量长上来
     * 之后变成线上事故，而不是一条编译期错误。
     *
     * @param maxFanout 单个算子允许的扇出宽度上限。它同时是<strong>并发上限</strong>：
     *                  并行扇出下每个分支各占一条连接，这个值应当与连接池大小相称，
     *                  否则一次 check 就能把池抽干。
     */
    record Caps(boolean reverseIndex, boolean snapshotRead, boolean recursiveQuery, int maxFanout) {

        /** {@code maxFanout} 非正会让每个算子都直接超限，等于整个内核不可用——这种装配错误必须在构造期就炸。 */
        public Caps {
            if (maxFanout <= 0) {
                throw new IllegalArgumentException("maxFanout 必须为正");
            }
        }
    }
}
